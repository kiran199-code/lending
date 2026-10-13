export interface Page<T> { items: T[]; page: number; size: number; total: number; totalPages: number }

export interface AccountSummary {
  accountId: number; accountNumber: string; customerId: number; sanctionRefId: number; productCode: string;
  accountType: string; accountStatus: string; sanctionedAmount: number; disbursedAmount: number | null;
  interestRate: number; tenureMonths: number; emiAmount: number | null; nextDueDate: string | null;
  daysPastDue: number; createdAt: string;
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
  mortgageId: number | null; version: number; createdAt: string; updatedAt: string; figures: Figures;
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
  interestPaid: number | null; channel: string; status: string; idempotencyKey: string | null;
}
export interface PaymentResponse {
  rows: PaymentRow[]; totalReceived: number; principalReceived: number; interestReceived: number; failed: number;
}

export interface ValuationRow { valuationAmount: number; valuationDate: string; valuerRef: string | null; valuationType: string }
export interface MortgageDetail {
  mortgageId: number; accountId: number; propertyType: string; propertyAddress: string; valuationAmount: number;
  valuationDate: string; valuerRef: string | null; ltvRatio: number; chargeStatus: string;
  cersaiRegistrationRef: string | null; mortgageDeedRef: string | null; insurancePolicyRef: string | null;
  legalVerificationStatus: string | null; valuations: ValuationRow[];
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

export interface Me { username: string; statementEnabled: boolean; timezone: string }

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
  status: string; expectedVersion: number | null;
}

export interface MaintenanceStatus {
  resetEnabled: boolean;
  businessDateStatus: string;
  rowCounts: Record<string, number>;
  baseProducts: { productCode: string; productName: string; exists: boolean }[];
}
export interface ClearDataResult { deleted: Record<string, number>; totalRows: number; clearedBy: string; message: string }
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
