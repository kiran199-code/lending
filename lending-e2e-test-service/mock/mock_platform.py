#!/usr/bin/env python3
"""
Minimal stand-in for the lending platform, used ONLY to develop and self-test the E2E suite (the suite's own tests of the
tests). It is not part of the deliverable's runtime and is never packaged into any lending artifact.

  python3 mock/mock_platform.py --mode impl    # batch jobs do real work  -> a healthy platform
  python3 mock/mock_platform.py --mode stubs   # batch jobs complete with 0 records (today's real behaviour)
  python3 mock/mock_platform.py --mode bugs    # impl, plus seeded defects the suite must catch (incl. an admin DPD on the wall clock)

The mock keeps a platform business date like lending-service does (GET/PUT /api/v1/business-date, /eod/start, /advance),
refuses payments and accrual runs dated after it, and serves the batch EOD API (POST /eod/run, GET /eod/status).
Set MOCK_ENFORCE_NO_FUTURE=0 to simulate lending-service with BUSINESS_DATE_ENFORCE_NO_FUTURE=false.

A fourth service, the Limits and Collateral service (LCM), listens on 18083 (Basic lcm-service/x, X-Actor required on writes). It models
collateral (DRAFT -> ACTIVE after legal + approved valuation + charge + insurance), facilities (DRAFT -> ACTIVE by maker-checker),
allocations, availability and collateral-cover, and lending-service's secured-loan rule: a MORTGAGE-type loan is disbursed only when
its facility is covered by ACTIVE collateral. A drawing or repayment reaches LCM about a second after the loan call, like the real outbox.
  MOCK_GATE=0        seeds a defect: the secured-loan rule is missing (SEC-06 must fail)
  MOCK_LCM_MODE=off  lending-service with lending.lcm.mode=off (secured loans must be refused as COLLATERAL_NOT_CHECKED)
  --mode bugs        also: a part-payment does not reduce the facility's utilisation (SEC-09 must fail)

Ports: lending 18080, batch 18081, admin 18082, LCM 18083. Basic auth on batch/admin: batch-admin/x and admin/x.
"""
import argparse, base64, json, os, threading, datetime as dt
from decimal import Decimal as D, ROUND_HALF_UP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

MODE = "impl"
LOCK = threading.RLock()
ACCOUNTS = {}      # id -> dict
SEQ = {"acct": 1000, "txn": 1, "exec": 1}
IDEM = {}
EXECS = {}
ACCRUALS = {}      # (id, date) -> dict
OUTBOX = []
BD = {"date": dt.date.today().isoformat(), "status": "OPEN"}     # the platform business date (owned by "lending-service")
EOD = {"last": None}
DAILY_JOBS = ["DEMAND_RAISE_DAILY","INTEREST_ACCRUAL_DAILY","PENAL_INTEREST_CALCULATION_DAILY","DPD_UPDATE_DAILY","NPA_CLASSIFICATION_CHECK_DAILY","SOD_EOD_BALANCING_DAILY",
              "GL_POSTING_RECONCILIATION_DAILY"]

PRODUCTS = {
 "PL01": dict(name="Personal Loan", sched="EMI", dc="ACT_365", rate="10.5", amin=50000, amax=5000000, tmin=6, tmax=84, freqs=["MONTHLY"], mor=0, bal=None, step=None),
 "TL01": dict(name="Term Loan", sched="EQUAL_PRINCIPAL", dc="THIRTY_E_360", rate="11.0", amin=100000, amax=20000000, tmin=12, tmax=120, freqs=["MONTHLY","QUARTERLY","HALF_YEARLY"], mor=0, bal=None, step=None),
 "BB01": dict(name="Bridge", sched="INTEREST_ONLY", dc="ACT_360", rate="12.0", amin=500000, amax=50000000, tmin=3, tmax=36, freqs=["MONTHLY","QUARTERLY"], mor=0, bal=None, step=None),
 "AB01": dict(name="Auto balloon", sched="BALLOON", dc="ACT_365", rate="9.5", amin=100000, amax=5000000, tmin=12, tmax=84, freqs=["MONTHLY"], mor=0, bal="30", step=None),
 "SU01": dict(name="Step-up", sched="STEP_UP_EMI", dc="ACT_365", rate="12.0", amin=50000, amax=3000000, tmin=12, tmax=84, freqs=["MONTHLY","QUARTERLY"], mor=0, bal=None, step="5"),
 "EM01": dict(name="Edu moratorium", sched="EMI", dc="THIRTY_360", rate="9.5", amin=100000, amax=5000000, tmin=24, tmax=144, freqs=["MONTHLY","QUARTERLY"], mor=12, bal=None, step=None),
 "HL01": dict(name="Home loan", type="MORTGAGE", sched="EMI", dc="ACT_365", rate="8.75", amin=500000, amax=50000000, tmin=60, tmax=360, freqs=["MONTHLY"], mor=0, bal=None, step=None),
 "AL01": dict(name="Auto floating", sched="EMI", dc="ACT_365", rate="9.75", amin=100000, amax=5000000, tmin=12, tmax=84, freqs=["MONTHLY"], mor=0, bal=None, step=None, floating=True),
}
PERIOD = {"MONTHLY":1,"QUARTERLY":3,"HALF_YEARLY":6,"YEARLY":12}
BUCKETS = [("ACTIVE",0),("NPA_SMA0",30),("NPA_SMA1",60),("NPA_SMA2",90),("NPA_SUBSTANDARD",365),("NPA_DOUBTFUL",1095),("NPA_LOSS",10**9)]

def q(x): return D(x).quantize(D("0.01"), rounding=ROUND_HALF_UP)
def d(s): return dt.date.fromisoformat(s)
def add_months(day, n):
    m = day.month - 1 + n; y = day.year + m // 12; m = m % 12 + 1
    import calendar
    return dt.date(y, m, min(day.day, calendar.monthrange(y, m)[1]))

def days30(a, b, euro):
    d1, d2 = a.day, b.day
    import calendar
    if euro: d1, d2 = min(d1, 30), min(d2, 30)
    else:
        a_last = a.month == 2 and a.day == calendar.monthrange(a.year, 2)[1]
        b_last = b.month == 2 and b.day == calendar.monthrange(b.year, 2)[1]
        if a_last and b_last: d2 = 30
        if a_last: d1 = 30
        if d2 == 31 and d1 >= 30: d2 = 30
        if d1 == 31: d1 = 30
    return 360*(b.year-a.year) + 30*(b.month-a.month) + (d2-d1)

def yf(a, b, conv):
    if conv == "ACT_365": return D((b-a).days)/D(365)
    if conv == "ACT_360": return D((b-a).days)/D(360)
    if conv == "THIRTY_360": return D(days30(a,b,False))/D(360)
    if conv == "THIRTY_E_360": return D(days30(a,b,True))/D(360)
    tot = D(0); cur = a
    while cur < b:
        ny = dt.date(cur.year+1,1,1); end = min(ny, b)
        L = 366 if (cur.year%4==0 and (cur.year%100!=0 or cur.year%400==0)) else 365
        tot += D((end-cur).days)/D(L); cur = end
    return tot

def basis(conv, day):
    if conv == "ACT_365": return 365
    if conv == "ACT_ACT": return 366 if (day.year%4==0 and (day.year%100!=0 or day.year%400==0)) else 365
    return 360

def build_schedule(a, principal, first_due, n, from_no=1, start=None):
    period = PERIOD[a["repaymentFrequency"]]; r = D(a["interestRate"])/100; conv = a["dayCountConvention"]
    st = a["scheduleType"]; mor = a["moratoriumMonths"]//period
    dues = [add_months(first_due, i*period) for i in range(n)]
    starts = [start or add_months(first_due, -period)] + dues[:-1]
    k = max(n - mor, 1)
    avg = sum(yf(starts[i], dues[i], conv) for i in range(mor, n))/D(max(n-mor,1))
    i_p = r*avg
    rows = []; opening = principal
    balloon = q(principal*D(a["balloonPercent"] or 0)/100) if st == "BALLOON" else D(0)
    def annuity(P, count, ip, fv=D(0)):
        if ip == 0: return (P-fv)/count
        return (P - fv/((1+ip)**count))*ip/(1-(1+ip)**(-count))
    emi = q(annuity(principal, k, i_p, balloon)) if st in ("EMI","BALLOON") else None
    if st == "STEP_UP_EMI":
        g = D(a["stepUpPercent"] or 0)/100
        lo, hi = D(0), principal
        for _ in range(60):
            mid = (lo+hi)/2; bal = principal
            for i in range(n):
                interest = bal*r*yf(starts[i], dues[i], conv)
                e = mid*((1+g)**((i-mor)//(12//period) if i>=mor and 12//period>0 else 0)) if i>=mor else interest
                bal = bal - (e-interest)
            if bal > 0: lo = mid
            else: hi = mid
        base = (lo+hi)/2
    for i in range(n):
        interest = q(opening*r*yf(starts[i], dues[i], conv))
        last = i == n-1
        if i < mor: principal_c = D(0)
        elif st == "EQUAL_PRINCIPAL": principal_c = q(principal/k)
        elif st == "INTEREST_ONLY": principal_c = D(0)
        elif st == "STEP_UP_EMI":
            e = base*((1+D(a["stepUpPercent"] or 0)/100)**((i-mor)//max(12//period,1)))
            principal_c = q(e-interest)
        else: principal_c = emi-interest
        if last or principal_c > opening: principal_c = opening
        bal = opening - principal_c
        rows.append(dict(installmentNumber=from_no+i, dueDate=dues[i].isoformat(), principalComponent=str(principal_c), interestComponent=str(interest),
                         outstandingBalance=str(bal), installmentStatus="PENDING", scheduleVersion=1))
        opening = bal
    return rows, (str(emi) if emi else None)

def outstanding(a):
    paid = [r for r in a["schedule"] if r["installmentStatus"] == "PAID"]
    return D(paid[-1]["outstandingBalance"]) if paid else D(a.get("openPrincipal") or 0)

def classify(dpd):
    for name, mx in BUCKETS:
        if dpd <= mx: return name

def account_json(a):
    return {k: a[k] for k in ("accountId","accountNumber","customerId","sanctionRefId","productCode","sanctionedAmount","disbursedAmount","tenureMonths",
            "scheduleType","rateType","interestRate","dayCountConvention","repaymentFrequency","moratoriumMonths","balloonPercent","stepUpPercent","emiAmount","emiStartDate","accountStatus","version","accountType","limitFacilityRef")}

def txn(a, kind, amount, pp, ip, no, value_date, key):
    t = dict(transactionId=SEQ["txn"], valueDate=value_date, accountId=a["accountId"], installmentNumber=no, transactionType=kind, amount=str(amount),
             principalPaid=str(pp), interestPaid=str(ip), paymentChannel="ONLINE", paymentStatus="SUCCESS", idempotencyKey=key)
    SEQ["txn"] += 1; a["txns"].append(t); return t

def run_accrual(day):
    n = 0
    for a in ACCOUNTS.values():
        if a["accountStatus"] in ("PENDING_DISBURSEMENT","CLOSED","FORECLOSED","WRITTEN_OFF"): continue
        o = outstanding(a)
        if o <= 0 or (a["accountId"], day) in ACCRUALS: continue
        amt = q(o*D(a["interestRate"])/(100*basis(a["dayCountConvention"], d(day))))
        if MODE == "bugs" and a["productCode"] == "AB01": amt = q(amt*D("1.05"))
        ACCRUALS[(a["accountId"], day)] = dict(businessDate=day, accountId=a["accountId"], accountNumber=a["accountNumber"], accruedAmount=str(amt), outstandingPrincipal=str(o))
        n += 1
    return n

def run_dpd(day):
    n = 0; today = d(day)
    for a in ACCOUNTS.values():
        if a["accountStatus"] in ("PENDING_DISBURSEMENT","CLOSED","FORECLOSED","WRITTEN_OFF"): continue
        unpaid = [d(r["dueDate"]) for r in a["schedule"] if r["installmentStatus"] != "PAID" and d(r["dueDate"]) < today]
        dpd = max([(today-x).days for x in unpaid], default=0)
        a["accountStatus"] = classify(dpd)
        if MODE == "bugs" and a["productCode"] == "TL01" and dpd > 60: a["accountStatus"] = "NPA_SMA1"
        for r in a["schedule"]:
            if r["installmentStatus"] == "PENDING" and d(r["dueDate"]) < today: r["installmentStatus"] = "OVERDUE"
        n += 1
    return n

def run_job(job, day):
    read = 0
    if MODE != "stubs":
        if job == "INTEREST_ACCRUAL_DAILY": read = run_accrual(day)
        elif job == "DPD_UPDATE_DAILY": read = run_dpd(day)
        elif job == "NPA_CLASSIFICATION_CHECK_DAILY": read = len([a for a in ACCOUNTS.values()])
    e = dict(jobExecutionId=SEQ["exec"], jobName=job, status="STARTED", exitCode="EXECUTING", businessDate=day, readCount=read, writeCount=read, skipCount=0)
    SEQ["exec"] += 1; EXECS[e["jobExecutionId"]] = e
    return e

def not_future(day):
    """lending-service refuses value dates after the business date."""
    return os.environ.get("MOCK_ENFORCE_NO_FUTURE", "1") == "0" or day <= BD["date"]

# ---------------------------------------------------------------------------------------------------- LCM (Limits and Collateral)
import time
COLLATERAL = {}    # ref -> dict
FACILITIES = {}    # ref -> dict
ALLOCS = {}        # id -> dict
VALS = {}          # id -> dict
CHANGES = {}       # id -> dict
LCM_Q = []         # (ready_at, facility, kind, amount)
LSEQ = {"alloc": 0, "val": 0, "chg": 0}

def lcm_later(fac, kind, amount):
    LCM_Q.append((time.time() + 1.2, fac, kind, amount))

def lcm_flush():
    now = time.time(); keep = []
    for ev in LCM_Q:
        if ev[0] > now: keep.append(ev); continue
        _, fac, kind, amt = ev; f = FACILITIES.get(fac)
        if not f: continue
        if kind == "draw": f["utilised"] += amt; f["drawnTotal"] += amt
        elif kind == "repay": f["utilised"] = max(D(0), f["utilised"] - amt)
        elif kind == "close": f["utilised"] = D(0)
    LCM_Q[:] = keep

def cover_of(fac):
    f = FACILITIES[fac]; items = []
    for al in ALLOCS.values():
        c = COLLATERAL[al["collateralRef"]]
        if al["facilityRef"] == fac and al["status"] == "ACTIVE" and c["status"] == "ACTIVE" and c["customerId"] == f["customerId"]:
            lendable = c["lendableValue"]
            items.append(dict(collateralRef=c["collateralRef"], collateralType=c["collateralType"], allocatedTo=fac, allocationPct=str(al["allocationPct"]),
                              lendableValue=str(lendable), coveredValue=str(q(lendable * D(str(al["allocationPct"])) / 100))))
    return dict(facilityRef=fac, customerId=f["customerId"], covered=bool(items), coveredValue=str(sum((D(i["coveredValue"]) for i in items), D(0))), items=items)

def secured_gate(a):
    """lending-service: a MORTGAGE-type loan is disbursed only against collateral LCM confirms. Returns (code, message) or None."""
    if PRODUCTS[a["productCode"]].get("type") != "MORTGAGE" or os.environ.get("MOCK_GATE", "1") == "0": return None
    mode = os.environ.get("MOCK_LCM_MODE", "live")
    if mode != "live":
        return ("COLLATERAL_NOT_CHECKED", "Account %s is a secured loan and collateral is held in LCM, but lending.lcm.mode is %s." % (a["accountId"], mode))
    ref = a.get("limitFacilityRef")
    if not ref: return ("NO_FACILITY", "Account %s is a secured loan and has no limit facility." % a["accountId"])
    lcm_flush()
    if not cover_of(ref)["covered"]:
        return ("COLLATERAL_NOT_COVERED", "Account %s is a secured loan but facility %s has no ACTIVE collateral of the borrower allocated to it in LCM." % (a["accountId"], ref))
    return None

def unmet(c):
    out = []
    if c["legalStatus"] != "VERIFIED": out.append("LEGAL_NOT_VERIFIED")
    if not any(v["status"] == "APPROVED" for v in VALS.values() if v["collateralRef"] == c["collateralRef"]): out.append("NO_CURRENT_VALUATION")
    if c["requiresCharge"] and not c["chargeRegistered"]: out.append("CHARGE_NOT_REGISTERED")
    if c["requiresInsurance"] and not (c["insuranceExpiry"] and c["insuranceExpiry"] > dt.date.today().isoformat()): out.append("INSURANCE_MISSING_OR_EXPIRED")
    return out

def cview(c):
    return {k: v for k, v in c.items() if k not in ("requiresCharge", "requiresInsurance")} | dict(unmetForActivation=unmet(c) if c["status"] == "DRAFT" else [])

def fview(f):
    return {k: (str(v) if isinstance(v, D) else v) for k, v in f.items()}

class H(BaseHTTPRequestHandler):
    svc = "lending"
    def log_message(self, *a): pass
    def _send(self, code, obj):
        b = json.dumps(obj, default=str).encode(); self.send_response(code); self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def _auth(self):
        if self.svc == "lending": return True
        h = self.headers.get("Authorization","")
        if not h.startswith("Basic "): self._send(401, {"error":"auth"}); return False
        u = base64.b64decode(h[6:]).decode().split(":")[0]
        if u != {"batch": "batch-admin", "admin": "admin", "lcm": "lcm-service"}[self.svc]: self._send(401, {"error":"auth"}); return False
        return True
    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}
    def do_GET(self): self._route("GET")
    def do_POST(self): self._route("POST")
    def do_PUT(self): self._route("PUT")
    def _route(self, m):
        if not self._auth(): return
        u = urlparse(self.path); p = u.path; qs = {k: v[0] for k, v in parse_qs(u.query).items()}
        try:
            with LOCK: getattr(self, "_" + self.svc)(m, p, qs)
        except KeyError as e: self._send(404, {"error": str(e)})
    # ------------------------------------------------------------------ lending
    def _lending(self, m, p, qs):
        parts = p.strip("/").split("/")
        if p == "/v3/api-docs":
            return self._send(200, {"paths": {"/api/v1/loan-accounts": {"post": {"summary": "create"}}, "/api/v1/ledger-outbox": {"get": {"summary": "outbox"}},
                                              "/api/v1/loan-accounts/rate-resets": {"post": {"summary": "rate reset"}},
                                              "/api/v1/business-date": {"get": {"summary": "current business date"}}}})
        if p == "/api/v1/business-date" and m == "GET":
            return self._send(200, dict(businessDate=BD["date"], status=BD["status"], workingDay=True))
        if p == "/api/v1/business-date" and m == "PUT":
            t = self._body()["date"]
            if BD["status"] != "OPEN": return self._send(409, {"error": "end of day is running"})
            if t < BD["date"]: return self._send(400, {"error": "business date can only move forward"})
            BD["date"] = t; return self._send(200, dict(businessDate=BD["date"], status=BD["status"]))
        if p == "/api/v1/business-date/eod/start" and m == "POST":
            if BD["status"] != "OPEN": return self._send(409, {"error": "end of day already running"})
            BD["status"] = "EOD_RUNNING"; return self._send(200, dict(businessDate=BD["date"], status=BD["status"]))
        if p == "/api/v1/business-date/advance" and m == "POST":
            exp = self._body()["expectedBusinessDate"]
            if exp != BD["date"]:
                if exp < BD["date"]: return self._send(200, dict(businessDate=BD["date"], status=BD["status"]))
                return self._send(409, {"error": "expected date is ahead of the business date"})
            if BD["status"] != "EOD_RUNNING": return self._send(409, {"error": "end of day not started"})
            BD["date"] = (d(BD["date"]) + dt.timedelta(days=1)).isoformat(); BD["status"] = "OPEN"
            return self._send(200, dict(businessDate=BD["date"], status=BD["status"]))
        if p == "/api/v1/loan-products":
            return self._send(200, [dict(productCode=c, status="ACTIVE", accountType=v.get("type", "UNSECURED"), minSanctionedAmount=v["amin"], maxSanctionedAmount=v["amax"],
                    minTenureMonths=v["tmin"], maxTenureMonths=v["tmax"], minInterestRate=5, maxInterestRate=20, repaymentFrequency="MONTHLY",
                    allowedRepaymentFrequencies=v["freqs"]) for c, v in PRODUCTS.items()])
        if p == "/api/v1/loan-accounts" and m == "POST":
            b = self._body(); pr = PRODUCTS.get(b.get("productCode"))
            if not pr: return self._send(404, {"error":"unknown product"})
            if not (pr["amin"] <= b["sanctionedAmount"] <= pr["amax"]): return self._send(400, {"error":"amount outside product limits"})
            if b.get("limitFacilityRef") and b["limitFacilityRef"] not in FACILITIES:
                return self._send(422, {"message": "Limit check failed [NOT_FOUND]: Facility not found: " + b["limitFacilityRef"]})
            SEQ["acct"] += 1; i = SEQ["acct"]
            a = dict(accountId=i, accountNumber="LN%08d" % i, customerId=b["customerId"], sanctionRefId=b["sanctionRefId"], productCode=b["productCode"],
                     sanctionedAmount=b["sanctionedAmount"], disbursedAmount=None, tenureMonths=b["tenureMonths"], scheduleType=pr["sched"],
                     rateType="FLOATING" if pr.get("floating") else "FIXED", interestRate=str(b.get("interestRate", pr["rate"])),
                     dayCountConvention=b.get("dayCountConvention", pr["dc"]), repaymentFrequency=b.get("repaymentFrequency", pr["freqs"][0]),
                     moratoriumMonths=b.get("moratoriumMonths", pr["mor"]), balloonPercent=b.get("balloonPercent", pr["bal"]), stepUpPercent=b.get("stepUpPercent", pr["step"]),
                     emiAmount=None, emiStartDate=None, accountStatus="PENDING_DISBURSEMENT", version=0,
                     accountType=pr.get("type", "UNSECURED"), limitFacilityRef=b.get("limitFacilityRef"), schedule=[], txns=[], created=dt.datetime.now())
            ACCOUNTS[i] = a; return self._send(201, account_json(a))
        if len(parts) >= 4 and parts[:3] == ["api","v1","loan-accounts"] and parts[3].isdigit():
            a = ACCOUNTS[int(parts[3])]; rest = parts[4:]
            if not rest and m == "GET": return self._send(200, account_json(a))
            if rest == ["disburse"]:
                b = self._body()
                if a["accountStatus"] == "PENDING_DISBURSEMENT":
                    refused = secured_gate(a)
                    if refused: return self._send(422, {"status": 422, "message": "Limit check failed [%s]: %s" % refused})
                a["disbursedAmount"] = str(b["disbursedAmount"]); first = d(b["firstEmiDueDate"]); a["emiStartDate"] = b["firstEmiDueDate"]
                n = a["tenureMonths"]//PERIOD[a["repaymentFrequency"]]
                a["schedule"], a["emiAmount"] = build_schedule(a, D(a["disbursedAmount"]), first, n)
                a["accountStatus"] = "ACTIVE"; a["openPrincipal"] = a["disbursedAmount"]; OUTBOX.append(a["accountNumber"])
                if a.get("limitFacilityRef"): lcm_later(a["limitFacilityRef"], "draw", D(a["disbursedAmount"]))
                return self._send(200, account_json(a))
            if rest == ["schedule"]: return self._send(200, a["schedule"])
            if rest[:1] == ["payments"] and m == "GET": return self._send(200, a["txns"])
            if rest[:1] == ["payments"] and m == "POST":
                key = self.headers.get("Idempotency-Key")
                if key in IDEM: return self._send(200, IDEM[key])
                b = self._body(); kind = rest[1]
                vd = b.get("valueDate") or b.get("foreclosureDate")
                if vd and not not_future(vd): return self._send(400, {"error": "value date %s is after the business date %s" % (vd, BD["date"])})
                if a["accountStatus"] in ("CLOSED","FORECLOSED"): return self._send(409, {"error":"account closed"})
                if kind == "emi":
                    unpaid = [r for r in a["schedule"] if r["installmentStatus"] != "PAID"]
                    r = unpaid[0]; due = q(D(r["principalComponent"]) + D(r["interestComponent"]))
                    if D(str(b["amount"])) != due: return self._send(400, {"error":"EMI amount must equal exact instalment due %s" % due})
                    r["installmentStatus"] = "PAID"
                    pp, ip = D(r["principalComponent"]), D(r["interestComponent"])
                    if MODE == "bugs" and a["productCode"] == "PL01" and r["installmentNumber"] == 2: pp, ip = pp+D(10), ip-D(10)
                    t = txn(a, "EMI", due, pp, ip, r["installmentNumber"], b["valueDate"], key)
                    if a.get("limitFacilityRef"): lcm_later(a["limitFacilityRef"], "repay", D(r["principalComponent"]))
                    if all(x["installmentStatus"] == "PAID" for x in a["schedule"]): a["accountStatus"] = "CLOSED"
                    else: a["accountStatus"] = "ACTIVE" if not any(x["installmentStatus"] == "OVERDUE" for x in a["schedule"]) else a["accountStatus"]
                    IDEM[key] = t; return self._send(200, t)
                if kind == "part-payment":
                    amt = D(str(b["amount"])); o = outstanding(a); newp = o - amt
                    unpaid = [r for r in a["schedule"] if r["installmentStatus"] != "PAID"]
                    paid = [r for r in a["schedule"] if r["installmentStatus"] == "PAID"]
                    first = d(unpaid[0]["dueDate"]); sub = dict(a); sub["scheduleType"] = "EMI"; sub["moratoriumMonths"] = 0
                    rows, emi = build_schedule(sub, newp, first, len(unpaid), from_no=unpaid[0]["installmentNumber"], start=d(paid[-1]["dueDate"]) if paid else None)
                    if paid: paid[-1]["outstandingBalance"] = str(newp)
                    else: a["openPrincipal"] = str(newp)
                    a["schedule"] = paid + rows; a["emiAmount"] = emi
                    t = txn(a, "PART_PAYMENT", amt, amt, D(0), None, b["valueDate"], key); IDEM[key] = t
                    if a.get("limitFacilityRef") and MODE != "bugs": lcm_later(a["limitFacilityRef"], "repay", amt)
                    return self._send(200, t)
                if kind == "foreclosure":
                    o = outstanding(a); a["accountStatus"] = "FORECLOSED"
                    acc = sum(D(x["accruedAmount"]) for (i, _), x in ACCRUALS.items() if i == a["accountId"])
                    paid_i = sum(D(t["interestPaid"]) for t in a["txns"]); ip = max(q(acc - paid_i), D(0))
                    for r in a["schedule"]: r["installmentStatus"] = "PAID"
                    t = txn(a, "FORECLOSURE", o + ip, o, ip, None, b["foreclosureDate"], key); IDEM[key] = t
                    if a.get("limitFacilityRef"): lcm_later(a["limitFacilityRef"], "close", D(0))
                    return self._send(200, t)
        if p == "/api/v1/interest-accruals/run" and not not_future(qs["businessDate"]): return self._send(400, {"error": "future business date"})
        if p == "/api/v1/interest-accruals/run": run_accrual(qs["businessDate"]); return self._send(200, {"status":"COMPLETED"})
        if p == "/api/v1/loan-accounts/delinquency-classifications": run_dpd(self._body()["asOfDate"]); return self._send(200, {"status":"COMPLETED"})
        self._send(404, {"error":"no route " + p})
    # ------------------------------------------------------------------ lcm
    def _lcm(self, m, p, qs):
        base = "/api/v1/lcm"
        if not p.startswith(base): return self._send(404, {"error": "no route " + p})
        r = p[len(base):].strip("/").split("/"); actor = self.headers.get("X-Actor")
        if m != "GET" and not actor: return self._send(400, {"code": "ACTOR_REQUIRED", "message": "X-Actor header is required"})
        lcm_flush()
        def err(code, status, msg): return self._send(status, {"code": code, "message": msg})
        if r == ["collateral-types"]:
            return self._send(200, [dict(typeCode="GOLD", requiresCharge=False, requiresInsurance=False),
                                    dict(typeCode="RESIDENTIAL_PROPERTY", requiresCharge=True, requiresInsurance=True)])
        if r[0] == "collateral":
            if len(r) == 1 and m == "POST":
                b = self._body(); ref = b.get("collateralRef") or "COL-%d" % (len(COLLATERAL) + 1)
                if ref in COLLATERAL: return err("COLLATERAL_EXISTS", 409, "exists")
                typ = b["collateralType"]
                COLLATERAL[ref] = dict(collateralRef=ref, customerId=b["customerId"], collateralType=typ, description=b["description"], currencyCode=b["currencyCode"],
                    status="DRAFT", legalStatus="PENDING", chargeRegistered=False, chargeRef=None, insuranceExpiry=None, lendableValue=D(0), latestValuation=None,
                    requiresCharge=typ == "RESIDENTIAL_PROPERTY", requiresInsurance=typ == "RESIDENTIAL_PROPERTY")
                return self._send(201, cview(COLLATERAL[ref]))
            c = COLLATERAL[r[1]]
            if len(r) == 2 and m == "GET": return self._send(200, cview(c))
            op = r[2]
            if op == "legal-verification": c["legalStatus"] = self._body()["status"]; return self._send(200, cview(c))
            if op == "charge": c["chargeRegistered"] = True; c["chargeRef"] = self._body().get("chargeRef"); return self._send(200, cview(c))
            if op == "insurance": c["insuranceExpiry"] = self._body()["expiry"]; return self._send(200, cview(c))
            if op == "valuations" and m == "POST":
                b = self._body(); LSEQ["val"] += 1
                VALS[LSEQ["val"]] = dict(id=LSEQ["val"], collateralRef=c["collateralRef"], marketValue=D(str(b["marketValue"])), status="PENDING", maker=actor, checker=None)
                return self._send(201, {k: (str(v) if isinstance(v, D) else v) for k, v in VALS[LSEQ["val"]].items()})
            if op == "activate":
                u = unmet(c)
                if u: return err("ACTIVATION_REQUIREMENTS_NOT_MET", 422, "Cannot activate %s: %s" % (c["collateralRef"], ", ".join(u)))
                c["status"] = "ACTIVE"; return self._send(200, cview(c))
            if op == "release":
                if any(al["status"] == "ACTIVE" for al in ALLOCS.values() if al["collateralRef"] == c["collateralRef"]): return err("HAS_ALLOCATIONS", 422, "active allocations")
                c["status"] = "RELEASED"; return self._send(200, cview(c))
            if op == "allocations" and m == "POST":
                b = self._body()
                if c["status"] != "ACTIVE": return err("COLLATERAL_NOT_ACTIVE", 422, "collateral is %s" % c["status"])
                if b["facilityRef"] not in FACILITIES: return err("NOT_FOUND", 404, "Facility not found")
                LSEQ["alloc"] += 1
                ALLOCS[LSEQ["alloc"]] = dict(id=LSEQ["alloc"], collateralRef=c["collateralRef"], facilityRef=b["facilityRef"], allocationPct=D(str(b["allocationPct"])), status="ACTIVE")
                return self._send(201, {k: (str(v) if isinstance(v, D) else v) for k, v in ALLOCS[LSEQ["alloc"]].items()})
            if op == "allocations" and m == "GET":
                return self._send(200, [{k: (str(v) if isinstance(v, D) else v) for k, v in al.items()} for al in ALLOCS.values() if al["collateralRef"] == c["collateralRef"]])
        if r[0] == "valuations" and r[2] == "approve":
            v = VALS[int(r[1])]
            if actor == v["maker"]: return err("MAKER_CHECKER", 409, "The maker cannot approve their own valuation")
            v["status"] = "APPROVED"; v["checker"] = actor; c = COLLATERAL[v["collateralRef"]]
            c["lendableValue"] = q(v["marketValue"] * D("0.7")); return self._send(200, {"id": v["id"], "status": "APPROVED"})
        if r[0] == "allocations" and r[2] == "release":
            ALLOCS[int(r[1])]["status"] = "RELEASED"; return self._send(200, {"id": int(r[1]), "status": "RELEASED"})
        if r[0] == "facilities":
            if len(r) == 1 and m == "POST":
                b = self._body(); ref = b.get("facilityRef") or "FAC-%d" % (len(FACILITIES) + 1)
                if ref in FACILITIES: return err("FACILITY_EXISTS", 409, "exists")
                FACILITIES[ref] = dict(facilityRef=ref, customerId=b["customerId"], facilityType=b["facilityType"], status="DRAFT", sanctionedAmount=D(str(b["sanctionedAmount"])),
                                       currencyCode=b["currencyCode"], revolving=b["revolving"], dpGoverned=b["dpGoverned"], utilised=D(0), drawnTotal=D(0), reserved=D(0))
                return self._send(201, fview(FACILITIES[ref]))
            if r[1] == "changes" and len(r) == 4 and r[3] == "approve":
                ch = CHANGES[int(r[2])]
                if actor == ch["maker"]: return err("MAKER_CHECKER", 409, "The maker cannot approve their own change")
                ch["status"] = "APPROVED"; ch["checker"] = actor
                FACILITIES[ch["facilityRef"]]["status"] = {"ACTIVATE": "ACTIVE", "CLOSE": "CLOSED"}[ch["changeType"]]
                return self._send(200, ch)
            f = FACILITIES[r[1]]
            if len(r) == 2: return self._send(200, fview(f))
            if r[2] == "changes" and m == "POST":
                b = self._body(); LSEQ["chg"] += 1
                CHANGES[LSEQ["chg"]] = dict(id=LSEQ["chg"], facilityRef=f["facilityRef"], changeType=b["changeType"], status="PENDING", maker=actor, checker=None)
                return self._send(201, CHANGES[LSEQ["chg"]])
            if r[2] == "availability":
                cons = f["utilised"] if f["revolving"] else f["drawnTotal"]
                return self._send(200, dict(facilityRef=f["facilityRef"], status=f["status"], currencyCode=f["currencyCode"], sanctionedAmount=str(f["sanctionedAmount"]),
                    utilised=str(f["utilised"]), drawnTotal=str(f["drawnTotal"]), reserved=str(f["reserved"]), consumed=str(cons),
                    available=str(f["sanctionedAmount"] - cons - f["reserved"]), effectiveAvailable=str(max(D(0), f["sanctionedAmount"] - cons - f["reserved"])), overLimit=False))
            if r[2] == "collateral-cover": return self._send(200, cover_of(r[1]))
        self._send(404, {"error": "no route " + p})
    # ------------------------------------------------------------------ batch
    def _batch(self, m, p, qs):
        parts = p.strip("/").split("/")
        jobs = ["DEMAND_RAISE_DAILY","INTEREST_ACCRUAL_DAILY","PENAL_INTEREST_CALCULATION_DAILY","DPD_UPDATE_DAILY","NPA_CLASSIFICATION_CHECK_DAILY","SOD_EOD_BALANCING_DAILY",
                "GL_POSTING_RECONCILIATION_DAILY","EMI_BILLING_GENERATION_MONTHLY","INTEREST_CAPITALIZATION_MONTHLY","PROVISIONING_CALCULATION_MONTHLY"]
        if p == "/api/v1/batch-admin/jobs": return self._send(200, [dict(jobName=j, enabled=True) for j in jobs])
        if len(parts) == 6 and parts[4] and parts[5] == "trigger" and m == "POST":
            job = parts[4]; day = self._body().get("businessDate")
            if not not_future(day): return self._send(400, {"error": "business date %s is after the platform business date %s" % (day, BD["date"])})
            return self._send(200, run_job(job, day))
        if p == "/api/v1/batch-admin/eod/status":
            return self._send(200, dict(running=False, businessDate=dict(businessDate=BD["date"], status=BD["status"]), lastRun=EOD["last"]))
        if p == "/api/v1/batch-admin/eod/run" and m == "POST":
            day = BD["date"]; BD["status"] = "EOD_RUNNING"; results = []
            for job in DAILY_JOBS:
                e = run_job(job, day); e["status"] = "COMPLETED"; e["exitCode"] = "COMPLETED"
                results.append(dict(jobName=job, status="COMPLETED", message=None))
            BD["date"] = (d(day) + dt.timedelta(days=1)).isoformat(); BD["status"] = "OPEN"
            EOD["last"] = dict(businessDate=day, outcome="ADVANCED", newBusinessDate=BD["date"], jobs=results, error=None)
            return self._send(202, dict(started=True))
        if len(parts) == 6 and parts[5] == "executions":
            job = parts[4]; out = []
            for e in EXECS.values():
                if e["jobName"] == job:
                    if e["status"] == "STARTED": e["status"] = "COMPLETED"; e["exitCode"] = "COMPLETED"
                    out.append(e)
            return self._send(200, out[-10:])
        self._send(404, {"error":"no route " + p})
    # ------------------------------------------------------------------ admin
    def _admin(self, m, p, qs):
        parts = p.strip("/").split("/")
        if p == "/api/admin/me": return self._send(200, {"user":"admin"})
        if p == "/api/admin/business-date": return self._send(200, dict(businessDate=BD["date"], status=BD["status"], fromLending=True))
        if p == "/v3/api-docs": return self._send(200, {"paths": {"/api/admin/overdue": {"get": {"summary":"overdue"}}}})
        if p == "/api/admin/ledger-outbox":
            items = [dict(accountNumber=n) for n in OUTBOX]
            return self._send(200, {"page": dict(items=items, page=0, size=200, total=len(items), totalPages=1), "counts": {}})
        if len(parts) >= 4 and parts[2] == "accounts" and parts[3].isdigit():
            a = ACCOUNTS[int(parts[3])]; rest = parts[4:]
            if not rest:
                out = outstanding(a) if a["accountStatus"] not in ("CLOSED","FORECLOSED") else D(0)
                wall = dt.date.today() if MODE == "bugs" else d(BD["date"]); unpaid = [d(r["dueDate"]) for r in a["schedule"] if r["installmentStatus"] != "PAID" and d(r["dueDate"]) < wall]
                return self._send(200, dict(accountId=a["accountId"], accountNumber=a["accountNumber"], accountStatus=a["accountStatus"],
                                            figures=dict(outstandingPrincipal=str(out), daysPastDue=max([(wall-x).days for x in unpaid], default=0))))
            if rest == ["schedule"]:
                return self._send(200, dict(rows=[dict(installmentNumber=r["installmentNumber"], dueDate=r["dueDate"], status=r["installmentStatus"]) for r in a["schedule"]]))
            if rest == ["accruals"]:
                lo, hi = qs.get("from"), qs.get("to")
                rows = [x for (i, day), x in sorted(ACCRUALS.items()) if i == a["accountId"] and (not lo or day >= lo) and (not hi or day <= hi)]
                return self._send(200, dict(rows=rows, total="0", days=len(rows)))
            if rest == ["payments"]:
                return self._send(200, dict(rows=[dict(transactionId=t["transactionId"], amount=t["amount"], status=t["paymentStatus"]) for t in a["txns"]]))
        self._send(404, {"error":"no route " + p})

def serve(port, svc):
    cls = type("H_" + svc, (H,), {"svc": svc})
    s = ThreadingHTTPServer(("127.0.0.1", port), cls); threading.Thread(target=s.serve_forever, daemon=True).start()

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--mode", default="impl", choices=["impl","stubs","bugs"]); ap.add_argument("--base", type=int, default=18080)
    a = ap.parse_args(); MODE = a.mode
    serve(a.base, "lending"); serve(a.base+1, "batch"); serve(a.base+2, "admin"); serve(a.base+3, "lcm")
    print("mock platform (%s) on %d/%d/%d, LCM on %d" % (MODE, a.base, a.base+1, a.base+2, a.base+3), flush=True)
    threading.Event().wait()
