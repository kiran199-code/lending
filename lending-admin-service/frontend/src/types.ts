export interface Page<T> { items: T[]; page: number; size: number; total: number; totalPages: number }

export interface AccountSummary {
  accountId: number; accountNumber: string; customerId: number; sanctionRefId: number; productCode: string;
  accountType: string; accountStatus: string; sanctionedAmount: number; disbursedAmount: number | null;
  interestRate: number; tenureMonths: number; emiAmount: number | null; nextDueDate: string | null;
  daysPastDue: number; createdAt: string; currencyCode: string; branchCode: string;
  limitFacilityRef: string | null;
}

export interface Figures {
  installmentsTotal: number; installmentsPaid: number; nextDueDate: string | null; nextEmiAmount: number | null;
  overdueInstallments: number; overdueAmount: number; daysPastDue: number; outstandingPrincipal: number | null;
  totalPaid: number; totalAccrued: number | null; lastAccrualDate: string | null;
}

export interface AccountDetail {
  accountId: number; accountNumber: string; customerId: number; sanctionRefId: number; productCode: string;
  accountType: string; accountStatus: string; sanctionedAmount: number; disbursedAmount: number | null;
  tenureMonths: number; rateType: string; interestRate: number; benchmarkRateRef: string | null;
  spread: number | null; dayCountConvention: string; emiAmount: number | null; emiStartDate: string | null;
  version: number; createdAt: string; updatedAt: string; figures: Figures;
  currencyCode: string; branchCode: string; limitFacilityRef: string | null;
}

export interface ScheduleRow {
  installmentNumber: number; dueDate: string; principal: number; interest: number; emi: number;
  outstandingBalance: number; status: string; scheduleVersion: number; daysPastDue: number;
}
export interface ScheduleVersion { version: number; installments: number; generatedAt: string }
export interface ScheduleTotals {
  totalPrincipal: number; totalInterest: number; totalEmi: number; paidEmi: number; pendingEmi: number;
  overdueEmi: number; paid: number; pending: number; overdue: number;
}
export interface ScheduleResponse {
  accountId: number; selectedVersion: number | null; versions: ScheduleVersion[]; rows: ScheduleRow[];
  totals: ScheduleTotals;
}

export interface AccrualRow {
  businessDate: string; accountId: number; accountNumber: string; accruedAmount: number;
  outstandingPrincipal: number;
}
export interface AccrualResponse { rows: AccrualRow[]; total: number; days: number }

export interface PaymentRow {
  transactionId: number; valueDate: string; accountId: number; accountNumber: string;
  installmentNumber: number | null; type: string; amount: number; principalPaid: number | null;
  interestPaid: number | null; penalInterestPaid: number | null; channel: string; status: string; idempotencyKey: string | null;
}
export interface PaymentResponse {
  rows: PaymentRow[]; totalReceived: number; principalReceived: number; interestReceived: number; failed: number;
}

export interface UpcomingRow {
  accountId: number; accountNumber: string; customerId: number; productCode: string; accountStatus: string;
  installmentNumber: number; dueDate: string; principal: number; interest: number; emi: number; daysToDue: number;
}
export interface UpcomingResponse { page: Page<UpcomingRow>; totalAmount: number; from: string; to: string }

export interface OverdueRow {
  accountId: number; accountNumber: string; customerId: number; productCode: string; accountStatus: string;
  installmentNumber: number; dueDate: string; principal: number; interest: number; emi: number; daysPastDue: number;
}
export interface Bucket { bucket: string; accounts: number; amount: number }

export interface StatusCount { status: string; accounts: number; sanctioned: number; disbursed: number }
export interface DailyAmount { date: string; amount: number; count: number }
export interface Dashboard {
  asOf: string; totalAccounts: number; activeAccounts: number; totalDisbursed: number; outstandingPrincipal: number;
  overdueAccounts: number; overdueAmount: number; dueNext7Count: number; dueNext7Amount: number;
  collectionsMtd: number; collectionsMtdCount: number; lastAccrualDate: string | null; lastAccrualAmount: number;
  accrualMtd: number; outboxPending: number; outboxFailed: number; byStatus: StatusCount[];
  accrualSeries: DailyAmount[]; collectionSeries: DailyAmount[];
}

export interface OutboxRow {
  outboxId: number; accountId: number; accountNumber: string; eventType: string; postingStatus: string;
  retryCount: number; lastAttemptedAt: string | null; createdAt: string;
}
export interface OutboxResponse { page: Page<OutboxRow>; counts: Record<string, number> }
export interface BatchJob { jobName: string; category: string; type: string; cron: string; enabled: boolean; priority: number | null; timeoutMinutes: number | null }
export interface BatchExecution { executionId: number; jobName: string; status: string; exitCode: string | null; startTime: string | null; endTime: string | null }
export interface BatchOverview { jobs: BatchJob[]; recentExecutions: BatchExecution[]; note: string | null }

export interface Me { username: string; statementEnabled: boolean; timezone: string; roles?: string[] }

export interface BatchJobConfig {
  configId: number; jobName: string; category: string; type: string; cron: string | null;
  enabled: boolean; restartable: boolean; chunkSize: number | null; concurrency: number | null;
  throttleLimit: number | null; retryLimit: number; skipLimit: number; priority: number;
  timeoutMinutes: number | null; version: number; retryableExceptions: string | null;
  skippableExceptions: string | null; jobParams: string | null; updatedAt: string | null;
}
export interface BatchJobUpdate {
  enabled?: boolean; cronExpression?: string; restartable?: boolean; chunkSize?: number; concurrency?: number;
  throttleLimit?: number; retryLimit?: number; skipLimit?: number; priority?: number; timeoutMinutes?: number;
  expectedVersion?: number;
}
export interface BatchConfigResponse { jobs: BatchJobConfig[]; editable: boolean; note: string | null }
export interface ApplyResult { job: BatchJobConfig; applied: boolean; message: string }
export interface SettingRow {
  name: string; envVar: string; value: string | null; defaultValue: string; sandboxValue: string;
  description: string; secret: boolean; restartRequired: boolean;
}
export interface SettingGroup { title: string; settings: SettingRow[] }
export interface ServiceSettings { service: string; description: string; changeVia: string; liveValues: boolean; groups: SettingGroup[] }
export interface FixedRule { area: string; rule: string; value: string }
export interface ConfigOverview { services: ServiceSettings[]; fixedRules: FixedRule[]; batchEditable: boolean }

export interface Product {
  productId: number; productCode: string; productName: string; description: string | null;
  accountType: string; rateType: string; scheduleType: string; dayCountConvention: string;
  benchmarkRateRef: string | null; defaultInterestRate: number; defaultSpread: number | null;
  minInterestRate: number | null; maxInterestRate: number | null; minSpread: number | null; maxSpread: number | null;
  minSanctionedAmount: number | null; maxSanctionedAmount: number | null;
  minTenureMonths: number | null; maxTenureMonths: number | null;
  repaymentFrequency: string; allowedRepaymentFrequencies: string[];
  moratoriumMonths: number; maxMoratoriumMonths: number | null;
  balloonPercent: number; maxBalloonPercent: number | null;
  stepUpPercent: number; maxStepUpPercent: number | null;
  penalInterestEnabled: boolean; penalInterestRate: number; penalGraceDays: number; penalCapPercent: number | null;
  status: string; accountCount: number; version: number;
}

export interface ProductsResponse { products: Product[]; editable: boolean; note: string | null }
export interface ProductInput {
  productCode: string; productName: string; description: string | null;
  accountType: string; rateType: string; scheduleType: string; dayCountConvention: string;
  benchmarkRateRef: string | null; defaultInterestRate: number | null; defaultSpread: number | null;
  minInterestRate: number | null; maxInterestRate: number | null; minSpread: number | null; maxSpread: number | null;
  minSanctionedAmount: number | null; maxSanctionedAmount: number | null;
  minTenureMonths: number | null; maxTenureMonths: number | null;
  repaymentFrequency: string; allowedRepaymentFrequencies: string[];
  moratoriumMonths: number | null; maxMoratoriumMonths: number | null;
  balloonPercent: number | null; maxBalloonPercent: number | null;
  stepUpPercent: number | null; maxStepUpPercent: number | null;
  penalInterestEnabled: boolean; penalInterestRate: number | null; penalGraceDays: number | null; penalCapPercent: number | null;
  status: string; expectedVersion: number | null;
}

export interface LcmMaintenanceStatus {
  configured: boolean;
  reachable?: boolean;
  resetEnabled?: boolean;
  rowCounts?: Record<string, number>;
  error?: string;
}
export interface MaintenanceStatus {
  resetEnabled: boolean;
  businessDate: string;
  businessDateStatus: string;
  rowCounts: Record<string, number>;
  baseProducts: { productCode: string; productName: string; exists: boolean }[];
  lcm: LcmMaintenanceStatus;
}
export interface ClearDataResult {
  deleted: Record<string, number>;
  totalRows: number;
  clearedBy: string;
  businessDate: string;
  message: string;
  lcm?: { configured: boolean; totalRows?: number };
}
export interface BaseProductsResult { created: string[]; alreadyExisted: string[]; message: string }

export interface EodJobRow { jobName: string; status: string; startedAt: string | null; finishedAt: string | null; message: string | null }
export interface EodProgress {
  state: 'IDLE' | 'RUNNING' | 'ADVANCED' | 'FAILED';
  actor: string | null;
  businessDate: string | null;
  resumed: boolean;
  startedAt: string | null;
  finishedAt: string | null;
  currentJob: string | null;
  total: number;
  done: number;
  jobs: EodJobRow[];
  newBusinessDate: string | null;
  message: string | null;
}
export interface EodBusinessDate {
  businessDate: string; previousBusinessDate: string | null; nextBusinessDate: string | null;
  status: string; workingDay: boolean; systemDate: string | null;
}
export interface EodStatus {
  configured: boolean;
  reachable: boolean;
  error?: string;
  databaseBusinessDate: string | null;
  databaseStatus: string | null;
  running?: boolean;
  businessDate?: EodBusinessDate | null;
  progress?: EodProgress;
}
export interface EodRunResult { started: boolean; message: string }

export interface BusinessDateView { businessDate: string; status: string | null; fromLending: boolean }

/** What lending-service returns for a created or disbursed account. */
export interface LoanAccountResult {
  accountId: number; accountNumber: string; customerId: number; sanctionRefId: number; productCode: string;
  accountType: string; sanctionedAmount: number; disbursedAmount: number | null; tenureMonths: number;
  scheduleType: string; rateType: string; interestRate: number; repaymentFrequency: string;
  emiAmount: number | null; emiStartDate: string | null; accountStatus: string;
  currencyCode?: string; branchCode?: string; limitFacilityRef?: string | null;
}
/** What lending-service returns for an instalment, part-payment or foreclosure. */
export interface PaymentResult {
  transactionId: number; valueDate: string; accountId: number; installmentNumber: number | null;
  transactionType: string; amount: number; principalPaid: number | null; interestPaid: number | null; penalInterestPaid?: number | null;
  paymentChannel: string; paymentStatus: string; idempotencyKey: string;
  /** How the payment was applied; absent on transactions read back from history. */
  allocations?: AllocationLine[] | null; advanceBalance?: number | null;
}

/** One line of how a payment is (or would be) applied. component is PENALTY, INTEREST, PRINCIPAL or ADVANCE. */
export interface AllocationLine { installmentNumber: number | null; component: string; amount: number }

export interface DemandLine {
  installmentNumber: number; dueDate: string; principalDue: number; interestDue: number; principalPaid: number;
  interestPaid: number; principalOutstanding: number; interestOutstanding: number; penalOutstanding: number; outstanding: number;
  status: string; raised: boolean;
}
export interface AmountPayable {
  accountId: number; asOfDate: string; accountStatus: string; daysPastDue: number; demands: DemandLine[];
  overdueAmount: number; dueTodayAmount: number; penalInterest: number; advanceBalance: number; amountPayable: number;
  nextInstallment: { installmentNumber: number; dueDate: string; principal: number; interest: number; amount: number } | null;
}
export interface PaymentPreview {
  accountId: number; valueDate: string; amount: number; paymentType: string; lines: AllocationLine[];
  toPenalInterest: number; toInterest: number; toPrincipal: number; surplus: number; surplusTreatment: string | null;
  allowed: boolean; message: string | null;
}
export interface BulkItemResult { index: number; outcome: string; accountId: number | null; accountNumber: string | null; accountStatus: string | null; message: string | null }
export interface BulkResult { total: number; succeeded: number; failed: number; results: BulkItemResult[] }

// ---- accounting (read from lending-service /api/v1/accounting) ----
export interface JournalSummary {
  journalId: number; journalNo: string; eventCode: string; accountId: number | null; productCode: string | null;
  sourceType: string; sourceId: number | null; businessDate: string; valueDate: string; txnCurrency: string;
  postingMode: string; backdated: boolean; priorPeriod: boolean; reversalOfJournalId: number | null; narrative: string | null;
  templateId: number | null; templateVersion: number | null; createdAt: string; branchCode: string;
}
export interface JournalLine {
  lineId: number; lineSeq: number; ledgerId: number; ledgerCode: string; glAccountCode: string; roleCode: string; side: 'DR' | 'CR';
  amountTxn: number; currencyTxn: string; fxRate: number | null; amountBase: number; currencyBase: string; ruleId: number | null; narrative: string | null;
}
export interface JournalDetail { header: JournalSummary; lines: JournalLine[]; reversedByJournalId: number | null }
export interface LedgerInfo { ledgerId: number; ledgerCode: string; ledgerType: string; purpose: string; baseCurrency: string; deliveryMode: string; status: string }
export interface TrialBalanceRow {
  ledgerId: number; ledgerCode: string; glAccountCode: string; accountName: string; currency: string; debits: number; credits: number; netDebit: number; branchCode: string;
}
export interface CurrencyInfo { currencyCode: string; currencyName: string; minorUnits: number; active: boolean; base: boolean }
export interface BranchInfo { branchCode: string; branchName: string; active: boolean; defaultBranch: boolean }
export interface TrialBalance { asOf: string; rows: TrialBalanceRow[]; totalDebits: number; totalCredits: number; balanced: boolean }
export interface PostingException {
  exceptionId: number; eventCode: string; accountId: number | null; idempotencyKey: string; reason: string; errorMessage: string | null;
  eodGate: boolean; attempts: number; status: string; createdAt: string; lastAttemptAt: string | null; resolvedBy: string | null; resolvedNote: string | null;
}
export interface ExceptionGate { openGateExceptions: number; openExceptions: number }
export interface ReconciliationCheck {
  checkCode: string; status: string; expected: number; actual: number; difference: number; detail: string | null; businessDate: string; runAt: string | null;
}
export interface Reconciliation { businessDate: string; allPassed: boolean; checks: ReconciliationCheck[] }
export interface PostOutcome { status: string; journalId: number | null; journalNo: string | null; note: string | null }

// ------------------------------------------------------------------ limits and collateral (lending-lcm-service)

export interface Facility {
  facilityRef: string; customerId: number; parentRef: string | null; facilityType: string; productCode: string | null;
  currencyCode: string; branchCode: string | null; revolving: boolean; dpGoverned: boolean;
  sanctionedAmount: number; maxSingleAmount: number | null; status: string;
  effectiveFrom: string | null; expiryDate: string | null; sanctionRefId: number | null; source: string | null;
  utilised: number; drawnTotal: number; reserved: number; createdBy: string | null; approvedBy: string | null;
}
export interface FacilityChange {
  id: number; facilityRef: string; changeType: string; payload: string | null; reason: string | null; status: string;
  maker: string; checker: string | null; decisionNote: string | null; createdAt: string; decidedAt: string | null;
}
export interface FacilityExcess {
  id: number; facilityRef: string; amount: number; validFrom: string; validTo: string; reason: string; status: string;
  maker: string; checker: string | null; createdAt: string; decidedAt: string | null;
}
export interface Availability {
  facilityRef: string; status: string; currencyCode: string; revolving: boolean; dpGoverned: boolean;
  sanctionedAmount: number; activeExcess: number; drawingPower: number | null; effectiveLimit: number;
  utilised: number; drawnTotal: number; reserved: number; consumed: number; available: number;
  effectiveAvailable: number; overLimit: boolean;
}
/** One ACTIVE collateral that backs a facility, as the Limits & Collateral service reports it. */
export interface CoverItem {
  collateralRef: string; collateralType: string; allocatedTo: string; allocationPct: number; lendableValue: number; coveredValue: number;
}
export interface CollateralCover { facilityRef: string; customerId: number; covered: boolean; coveredValue: number; items: CoverItem[] }
export interface FacilityTree { facility: Availability; children: FacilityTree[] }
export interface LimitTxn {
  id: number; facilityRef: string; originFacilityRef: string | null; txnType: string; amount: number;
  dReserved: number; dUtilised: number; dDrawnTotal: number; referenceType: string | null; referenceId: string | null;
  actor: string | null; createdAt: string;
}
export interface Valuation {
  id: number; collateralRef: string; valuationDate: string; marketValue: number; forcedSaleValue: number | null;
  valuerRef: string | null; source: string | null; status: string; maker: string; checker: string | null; createdAt: string;
}
export interface Collateral {
  collateralRef: string; customerId: number; collateralType: string; description: string; currencyCode: string; status: string;
  legalStatus: string; chargeRegistered: boolean; chargeRef: string | null; chargeRegisteredOn: string | null;
  priorChargeAmount: number | null; insuranceExpiry: string | null; haircutPct: number; maxLtvPct: number;
  lendingMortgageId: number | null; source: string | null; latestValuation: Valuation | null;
  eligibleValue: number | null; lendableValue: number | null; unmetForActivation: string[];
}
export interface CollateralAllocation {
  id: number; collateralRef: string; facilityRef: string; allocationPct: number; status: string; createdBy: string | null;
  createdAt: string; releasedAt: string | null;
}
export interface DpContribution {
  collateralRef: string; allocationPct: number; baseValue: number; haircutPct: number; priorCharge: number | null;
  eligibleValue: number; maxLtvPct: number; lendableValue: number; contribution: number; zeroReason: string | null;
}
export interface DrawingPower { facilityRef: string; asOf: string; dpGoverned: boolean; drawingPower: number | null; contributions: DpContribution[] }
export interface CollateralType {
  typeCode: string; description: string; defaultHaircutPct: number; maxLtvPct: number; valuationValidityDays: number;
  requiresCharge: boolean; requiresInsurance: boolean;
}
export interface Breach {
  id: number; breachType: string; severity: string; facilityRef: string | null; collateralRef: string | null; detail: string;
  status: string; acknowledgedBy: string | null; detectedAt: string; closedAt: string | null;
}
export interface SweepResult { asOf: string; reservationsExpired: number; facilitiesExpired: number; breachesOpened: number; breachesClosed: number }
export interface Integrity { ok: boolean; differences: string[] }
