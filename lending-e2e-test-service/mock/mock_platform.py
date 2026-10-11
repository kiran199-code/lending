#!/usr/bin/env python3
"""
Minimal stand-in for the lending platform, used ONLY to develop and self-test the E2E suite (the suite's own tests of the
tests). It is not part of the deliverable's runtime and is never packaged into any lending artifact.

  python3 mock/mock_platform.py --mode impl    # batch jobs do real work  -> a healthy platform
  python3 mock/mock_platform.py --mode stubs   # batch jobs complete with 0 records (today's real behaviour)
  python3 mock/mock_platform.py --mode bugs    # impl, plus seeded defects the suite must catch

Ports: lending 18080, batch 18081, admin 18082. Basic auth on batch/admin: batch-admin/x and admin/x.
"""
import argparse, base64, json, threading, datetime as dt
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

PRODUCTS = {
 "PL01": dict(name="Personal Loan", sched="EMI", dc="ACT_365", rate="10.5", amin=50000, amax=5000000, tmin=6, tmax=84, freqs=["MONTHLY"], mor=0, bal=None, step=None),
 "TL01": dict(name="Term Loan", sched="EQUAL_PRINCIPAL", dc="THIRTY_E_360", rate="11.0", amin=100000, amax=20000000, tmin=12, tmax=120, freqs=["MONTHLY","QUARTERLY","HALF_YEARLY"], mor=0, bal=None, step=None),
 "BB01": dict(name="Bridge", sched="INTEREST_ONLY", dc="ACT_360", rate="12.0", amin=500000, amax=50000000, tmin=3, tmax=36, freqs=["MONTHLY","QUARTERLY"], mor=0, bal=None, step=None),
 "AB01": dict(name="Auto balloon", sched="BALLOON", dc="ACT_365", rate="9.5", amin=100000, amax=5000000, tmin=12, tmax=84, freqs=["MONTHLY"], mor=0, bal="30", step=None),
 "SU01": dict(name="Step-up", sched="STEP_UP_EMI", dc="ACT_365", rate="12.0", amin=50000, amax=3000000, tmin=12, tmax=84, freqs=["MONTHLY","QUARTERLY"], mor=0, bal=None, step="5"),
 "EM01": dict(name="Edu moratorium", sched="EMI", dc="THIRTY_360", rate="9.5", amin=100000, amax=5000000, tmin=24, tmax=144, freqs=["MONTHLY","QUARTERLY"], mor=12, bal=None, step=None),
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
            "scheduleType","rateType","interestRate","dayCountConvention","repaymentFrequency","moratoriumMonths","balloonPercent","stepUpPercent","emiAmount","emiStartDate","accountStatus","version")}

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

class H(BaseHTTPRequestHandler):
    svc = "lending"
    def log_message(self, *a): pass
    def _send(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def _auth(self):
        if self.svc == "lending": return True
        h = self.headers.get("Authorization","")
        if not h.startswith("Basic "): self._send(401, {"error":"auth"}); return False
        u = base64.b64decode(h[6:]).decode().split(":")[0]
        if u != ("batch-admin" if self.svc == "batch" else "admin"): self._send(401, {"error":"auth"}); return False
        return True
    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}
    def do_GET(self): self._route("GET")
    def do_POST(self): self._route("POST")
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
                                              "/api/v1/loan-accounts/rate-resets": {"post": {"summary": "rate reset"}}}})
        if p == "/api/v1/loan-products":
            return self._send(200, [dict(productCode=c, status="ACTIVE", accountType="UNSECURED", minSanctionedAmount=v["amin"], maxSanctionedAmount=v["amax"],
                    minTenureMonths=v["tmin"], maxTenureMonths=v["tmax"], minInterestRate=5, maxInterestRate=20, repaymentFrequency="MONTHLY",
                    allowedRepaymentFrequencies=v["freqs"]) for c, v in PRODUCTS.items()])
        if p == "/api/v1/loan-accounts" and m == "POST":
            b = self._body(); pr = PRODUCTS.get(b.get("productCode"))
            if not pr: return self._send(404, {"error":"unknown product"})
            if not (pr["amin"] <= b["sanctionedAmount"] <= pr["amax"]): return self._send(400, {"error":"amount outside product limits"})
            SEQ["acct"] += 1; i = SEQ["acct"]
            a = dict(accountId=i, accountNumber="LN%08d" % i, customerId=b["customerId"], sanctionRefId=b["sanctionRefId"], productCode=b["productCode"],
                     sanctionedAmount=b["sanctionedAmount"], disbursedAmount=None, tenureMonths=b["tenureMonths"], scheduleType=pr["sched"],
                     rateType="FLOATING" if pr.get("floating") else "FIXED", interestRate=str(b.get("interestRate", pr["rate"])),
                     dayCountConvention=b.get("dayCountConvention", pr["dc"]), repaymentFrequency=b.get("repaymentFrequency", pr["freqs"][0]),
                     moratoriumMonths=b.get("moratoriumMonths", pr["mor"]), balloonPercent=b.get("balloonPercent", pr["bal"]), stepUpPercent=b.get("stepUpPercent", pr["step"]),
                     emiAmount=None, emiStartDate=None, accountStatus="PENDING_DISBURSEMENT", version=0, schedule=[], txns=[], created=dt.datetime.now())
            ACCOUNTS[i] = a; return self._send(201, account_json(a))
        if len(parts) >= 4 and parts[:3] == ["api","v1","loan-accounts"] and parts[3].isdigit():
            a = ACCOUNTS[int(parts[3])]; rest = parts[4:]
            if not rest and m == "GET": return self._send(200, account_json(a))
            if rest == ["disburse"]:
                b = self._body(); a["disbursedAmount"] = str(b["disbursedAmount"]); first = d(b["firstEmiDueDate"]); a["emiStartDate"] = b["firstEmiDueDate"]
                n = a["tenureMonths"]//PERIOD[a["repaymentFrequency"]]
                a["schedule"], a["emiAmount"] = build_schedule(a, D(a["disbursedAmount"]), first, n)
                a["accountStatus"] = "ACTIVE"; a["openPrincipal"] = a["disbursedAmount"]; OUTBOX.append(a["accountNumber"])
                return self._send(200, account_json(a))
            if rest == ["schedule"]: return self._send(200, a["schedule"])
            if rest[:1] == ["payments"] and m == "GET": return self._send(200, a["txns"])
            if rest[:1] == ["payments"] and m == "POST":
                key = self.headers.get("Idempotency-Key")
                if key in IDEM: return self._send(200, IDEM[key])
                b = self._body(); kind = rest[1]
                if a["accountStatus"] in ("CLOSED","FORECLOSED"): return self._send(409, {"error":"account closed"})
                if kind == "emi":
                    unpaid = [r for r in a["schedule"] if r["installmentStatus"] != "PAID"]
                    r = unpaid[0]; due = q(D(r["principalComponent"]) + D(r["interestComponent"]))
                    if D(str(b["amount"])) != due: return self._send(400, {"error":"EMI amount must equal exact instalment due %s" % due})
                    r["installmentStatus"] = "PAID"
                    pp, ip = D(r["principalComponent"]), D(r["interestComponent"])
                    if MODE == "bugs" and a["productCode"] == "PL01" and r["installmentNumber"] == 2: pp, ip = pp+D(10), ip-D(10)
                    t = txn(a, "EMI", due, pp, ip, r["installmentNumber"], b["valueDate"], key)
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
                    t = txn(a, "PART_PAYMENT", amt, amt, D(0), None, b["valueDate"], key); IDEM[key] = t; return self._send(200, t)
                if kind == "foreclosure":
                    o = outstanding(a); a["accountStatus"] = "FORECLOSED"
                    acc = sum(D(x["accruedAmount"]) for (i, _), x in ACCRUALS.items() if i == a["accountId"])
                    paid_i = sum(D(t["interestPaid"]) for t in a["txns"]); ip = max(q(acc - paid_i), D(0))
                    for r in a["schedule"]: r["installmentStatus"] = "PAID"
                    t = txn(a, "FORECLOSURE", o + ip, o, ip, None, b["foreclosureDate"], key); IDEM[key] = t; return self._send(200, t)
        if p == "/api/v1/interest-accruals/run": run_accrual(qs["businessDate"]); return self._send(200, {"status":"COMPLETED"})
        if p == "/api/v1/loan-accounts/delinquency-classifications": run_dpd(self._body()["asOfDate"]); return self._send(200, {"status":"COMPLETED"})
        self._send(404, {"error":"no route " + p})
    # ------------------------------------------------------------------ batch
    def _batch(self, m, p, qs):
        parts = p.strip("/").split("/")
        jobs = ["INTEREST_ACCRUAL_DAILY","PENAL_INTEREST_CALCULATION_DAILY","DPD_UPDATE_DAILY","NPA_CLASSIFICATION_CHECK_DAILY","SOD_EOD_BALANCING_DAILY",
                "GL_POSTING_RECONCILIATION_DAILY","EMI_BILLING_GENERATION_MONTHLY","INTEREST_CAPITALIZATION_MONTHLY","PROVISIONING_CALCULATION_MONTHLY"]
        if p == "/api/v1/batch-admin/jobs": return self._send(200, [dict(jobName=j, enabled=True) for j in jobs])
        if len(parts) == 6 and parts[4] and parts[5] == "trigger" and m == "POST":
            job = parts[4]; day = self._body().get("businessDate"); read = 0
            if MODE != "stubs":
                if job == "INTEREST_ACCRUAL_DAILY": read = run_accrual(day)
                elif job == "DPD_UPDATE_DAILY": read = run_dpd(day)
                elif job == "NPA_CLASSIFICATION_CHECK_DAILY": read = len([a for a in ACCOUNTS.values()])
            e = dict(jobExecutionId=SEQ["exec"], jobName=job, status="STARTED", exitCode="EXECUTING", businessDate=day, readCount=read, writeCount=read, skipCount=0)
            SEQ["exec"] += 1; EXECS[e["jobExecutionId"]] = e; return self._send(200, e)
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
        if p == "/v3/api-docs": return self._send(200, {"paths": {"/api/admin/overdue": {"get": {"summary":"overdue"}}}})
        if p == "/api/admin/ledger-outbox":
            items = [dict(accountNumber=n) for n in OUTBOX]
            return self._send(200, {"page": dict(items=items, page=0, size=200, total=len(items), totalPages=1), "counts": {}})
        if len(parts) >= 4 and parts[2] == "accounts" and parts[3].isdigit():
            a = ACCOUNTS[int(parts[3])]; rest = parts[4:]
            if not rest:
                out = outstanding(a) if a["accountStatus"] not in ("CLOSED","FORECLOSED") else D(0)
                wall = dt.date.today(); unpaid = [d(r["dueDate"]) for r in a["schedule"] if r["installmentStatus"] != "PAID" and d(r["dueDate"]) < wall]
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
    serve(a.base, "lending"); serve(a.base+1, "batch"); serve(a.base+2, "admin")
    print("mock platform (%s) on %d/%d/%d" % (MODE, a.base, a.base+1, a.base+2), flush=True)
    threading.Event().wait()
