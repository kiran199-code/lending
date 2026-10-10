package com.bank.lending.statement;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link LoanStatementData} as an A4 PDF a customer can read, save or print: account
 * details, key figures, the repayment schedule and the payments received.
 *
 * <p>Wording is for the customer, not for operations: internal status codes (for example the NPA
 * buckets) are shown as plain language, and internal identifiers such as the database account ID,
 * mortgage ID, record version or idempotency keys never appear.
 */
@Component
public class LoanStatementPdfRenderer {

    private static final Color INK = new Color(0x1B, 0x27, 0x33);
    private static final Color MUTED = new Color(0x5A, 0x67, 0x73);
    private static final Color RULE = new Color(0xC9, 0xD3, 0xDB);
    private static final Color BRAND = new Color(0x1C, 0x5D, 0x99);
    private static final Color ZEBRA = new Color(0xF3, 0xF6, 0xF9);
    private static final Color GOOD = new Color(0x1B, 0x7F, 0x4B);
    private static final Color BAD = new Color(0xB3, 0x26, 0x1E);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final String institutionName;
    private final String currency;
    private final String contactLine;

    public LoanStatementPdfRenderer(
            @Value("${lending.statement.institution-name:Lending Bank}") String institutionName,
            @Value("${lending.statement.currency:INR}") String currency,
            @Value("${lending.statement.contact-line:For any query, contact your branch or customer care and quote your account number.}")
            String contactLine) {
        this.institutionName = institutionName;
        this.currency = currency;
        this.contactLine = contactLine;
    }

    public byte[] render(LoanStatementData data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 40, 56);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new FooterEvent(
                    "Loan statement " + data.account().getAccountNumber() + "  |  Generated on "
                            + data.statementDate().format(DATE) + "  |  Computer generated, no signature required"));
            document.addTitle("Loan statement " + data.account().getAccountNumber());
            document.addAuthor(institutionName);
            document.addSubject("Loan account statement");
            document.open();

            Fmt fmt = new Fmt();
            addHeader(document, data, fmt);
            addAccountDetails(document, data, fmt);
            addSummary(document, data, fmt);
            addSchedule(document, data, fmt);
            addPayments(document, data, fmt);
            addNotes(document);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not build the loan statement PDF", e);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------------------------------

    private void addHeader(Document document, LoanStatementData data, Fmt fmt) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{60, 40});

        PdfPCell left = noBorder();
        left.addElement(new Paragraph(institutionName, font(17, Font.BOLD, BRAND)));
        left.addElement(new Paragraph("Loan Account Statement", font(12, Font.NORMAL, INK)));
        table.addCell(left);

        PdfPCell right = noBorder();
        Paragraph p = new Paragraph("Statement date", font(8, Font.NORMAL, MUTED));
        p.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(p);
        Paragraph d = new Paragraph(data.statementDate().format(DATE), font(11, Font.BOLD, INK));
        d.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(d);
        Paragraph a = new Paragraph("Account " + data.account().getAccountNumber(), font(9, Font.NORMAL, MUTED));
        a.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(a);
        table.addCell(right);

        table.setSpacingAfter(4);
        document.add(table);

        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        PdfPCell line = new PdfPCell(new Phrase(" ", font(2, Font.NORMAL, INK)));
        line.setBorder(Rectangle.BOTTOM);
        line.setBorderColor(BRAND);
        line.setBorderWidth(1.5f);
        rule.addCell(line);
        rule.setSpacingAfter(10);
        document.add(rule);
    }

    private void addAccountDetails(Document document, LoanStatementData data, Fmt fmt) throws DocumentException {
        LoanAccount a = data.account();
        document.add(sectionTitle("Account details"));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{20, 30, 20, 30});

        detail(table, "Account number", a.getAccountNumber());
        detail(table, "Customer ID", String.valueOf(a.getCustomerId()));
        detail(table, "Loan type", accountTypeLabel(a.getAccountType()));
        detail(table, "Product code", a.getProductCode());
        detail(table, "Sanctioned amount", fmt.money(a.getSanctionedAmount()));
        detail(table, "Disbursed amount",
                a.getDisbursedAmount() != null ? fmt.money(a.getDisbursedAmount()) : "Not yet disbursed");
        detail(table, "Tenure", a.getTenureMonths() + " months");
        detail(table, "Interest rate", rateText(a, fmt));
        detail(table, "Monthly instalment (EMI)", a.getEmiAmount() != null ? fmt.money(a.getEmiAmount()) : "N/A");
        detail(table, "First EMI date", a.getEmiStartDate() != null ? a.getEmiStartDate().format(DATE) : "N/A");
        detail(table, "Interest basis", dayCountLabel(a.getDayCountConvention()));
        detail(table, "Account status", statusLabel(a.getAccountStatus()));
        detail(table, "Account opened", a.getCreatedAt() != null ? date(a.getCreatedAt()) : "N/A");
        detail(table, "", "");

        table.setSpacingAfter(10);
        document.add(table);
    }

    private void addSummary(Document document, LoanStatementData data, Fmt fmt) throws DocumentException {
        document.add(sectionTitle("Where you stand"));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);

        String outstanding = data.outstandingPrincipal().map(fmt::money).orElse("N/A");
        String paidCount = data.installmentsTotal() == 0 ? "N/A"
                : data.installmentsPaid() + " of " + data.installmentsTotal();
        String next = data.nextInstallment()
                .map(e -> fmt.money(LoanStatementData.emiOf(e)) + " on " + e.getDueDate().format(DATE))
                .orElse(data.installmentsTotal() == 0 ? "N/A" : "None, all paid");

        keyFigure(table, "Outstanding principal", outstanding, INK);
        keyFigure(table, "Total paid so far", fmt.money(data.totalPaid()), INK);
        keyFigure(table, "Instalments paid", paidCount, INK);
        keyFigure(table, "Next instalment due", next, INK);

        if (data.overdueAmount().signum() > 0) {
            PdfPCell warn = new PdfPCell(new Phrase("Overdue amount: " + fmt.money(data.overdueAmount())
                    + ". Please pay as soon as possible to avoid further charges.", font(9, Font.BOLD, BAD)));
            warn.setColspan(4);
            warn.setBorder(Rectangle.NO_BORDER);
            warn.setPaddingTop(6);
            table.addCell(warn);
        }
        table.setSpacingAfter(10);
        document.add(table);
    }

    private void addSchedule(Document document, LoanStatementData data, Fmt fmt) throws DocumentException {
        document.add(sectionTitle("Repayment schedule"));
        List<AmortizationScheduleEntry> schedule = data.schedule();
        if (schedule.isEmpty()) {
            document.add(note("The repayment schedule is created when the loan is disbursed."));
            return;
        }

        PdfPTable table = new PdfPTable(7);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{7, 15, 16, 16, 16, 18, 12});
        table.setHeaderRows(1);

        headerCell(table, "No.", Element.ALIGN_CENTER);
        headerCell(table, "Due date", Element.ALIGN_LEFT);
        headerCell(table, "EMI (" + currency + ")", Element.ALIGN_RIGHT);
        headerCell(table, "Principal", Element.ALIGN_RIGHT);
        headerCell(table, "Interest", Element.ALIGN_RIGHT);
        headerCell(table, "Balance after", Element.ALIGN_RIGHT);
        headerCell(table, "Status", Element.ALIGN_CENTER);

        BigDecimal totalPrincipal = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;
        int row = 0;
        for (AmortizationScheduleEntry e : schedule) {
            Color bg = row++ % 2 == 1 ? ZEBRA : null;
            bodyCell(table, String.valueOf(e.getInstallmentNumber()), Element.ALIGN_CENTER, bg, INK, Font.NORMAL);
            bodyCell(table, e.getDueDate().format(DATE), Element.ALIGN_LEFT, bg, INK, Font.NORMAL);
            bodyCell(table, fmt.number(LoanStatementData.emiOf(e)), Element.ALIGN_RIGHT, bg, INK, Font.NORMAL);
            bodyCell(table, fmt.number(e.getPrincipalComponent()), Element.ALIGN_RIGHT, bg, INK, Font.NORMAL);
            bodyCell(table, fmt.number(e.getInterestComponent()), Element.ALIGN_RIGHT, bg, INK, Font.NORMAL);
            bodyCell(table, fmt.number(e.getOutstandingBalance()), Element.ALIGN_RIGHT, bg, INK, Font.NORMAL);
            bodyCell(table, installmentLabel(e.getInstallmentStatus()), Element.ALIGN_CENTER, bg,
                    installmentColor(e.getInstallmentStatus()), Font.BOLD);
            totalPrincipal = totalPrincipal.add(e.getPrincipalComponent());
            totalInterest = totalInterest.add(e.getInterestComponent());
        }

        totalCell(table, "Total", 2, Element.ALIGN_LEFT);
        totalCell(table, fmt.number(totalPrincipal.add(totalInterest)), 1, Element.ALIGN_RIGHT);
        totalCell(table, fmt.number(totalPrincipal), 1, Element.ALIGN_RIGHT);
        totalCell(table, fmt.number(totalInterest), 1, Element.ALIGN_RIGHT);
        totalCell(table, "", 2, Element.ALIGN_LEFT);

        table.setSpacingAfter(10);
        document.add(table);
    }

    private void addPayments(Document document, LoanStatementData data, Fmt fmt) throws DocumentException {
        document.add(sectionTitle("Payments received"));
        List<EmiTransaction> payments = data.payments();
        if (payments.isEmpty()) {
            document.add(note("No payments have been received yet."));
            return;
        }

        PdfPTable table = new PdfPTable(7);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{14, 19, 9, 15, 14, 14, 15});
        table.setHeaderRows(1);

        headerCell(table, "Date", Element.ALIGN_LEFT);
        headerCell(table, "Type", Element.ALIGN_LEFT);
        headerCell(table, "EMI no.", Element.ALIGN_CENTER);
        headerCell(table, "Amount (" + currency + ")", Element.ALIGN_RIGHT);
        headerCell(table, "Towards principal", Element.ALIGN_RIGHT);
        headerCell(table, "Towards interest", Element.ALIGN_RIGHT);
        headerCell(table, "Paid via", Element.ALIGN_LEFT);

        int row = 0;
        for (EmiTransaction p : payments) {
            Color bg = row++ % 2 == 1 ? ZEBRA : null;
            boolean processing = p.getPaymentStatus() == EmiTransaction.PaymentStatus.PENDING;
            bodyCell(table, p.getValueDate().format(DATE), Element.ALIGN_LEFT, bg, INK, Font.NORMAL);
            bodyCell(table, transactionLabel(p.getTransactionType()) + (processing ? " (processing)" : ""),
                    Element.ALIGN_LEFT, bg, processing ? MUTED : INK, Font.NORMAL);
            bodyCell(table, p.getInstallmentNumber() != null ? String.valueOf(p.getInstallmentNumber()) : "-",
                    Element.ALIGN_CENTER, bg, INK, Font.NORMAL);
            bodyCell(table, fmt.number(p.getAmount()), Element.ALIGN_RIGHT, bg, INK, Font.NORMAL);
            bodyCell(table, p.getPrincipalPaid() != null ? fmt.number(p.getPrincipalPaid()) : "-",
                    Element.ALIGN_RIGHT, bg, INK, Font.NORMAL);
            bodyCell(table, p.getInterestPaid() != null ? fmt.number(p.getInterestPaid()) : "-",
                    Element.ALIGN_RIGHT, bg, INK, Font.NORMAL);
            bodyCell(table, channelLabel(p.getPaymentChannel()), Element.ALIGN_LEFT, bg, INK, Font.NORMAL);
        }

        totalCell(table, "Total received", 3, Element.ALIGN_LEFT);
        totalCell(table, fmt.number(data.totalPaid()), 1, Element.ALIGN_RIGHT);
        totalCell(table, "", 3, Element.ALIGN_LEFT);

        table.setSpacingAfter(10);
        document.add(table);
    }

    private void addNotes(Document document) throws DocumentException {
        document.add(sectionTitle("Please note"));
        document.add(note("Amounts are in " + currency + ". Balances are the principal outstanding after each instalment is paid. "
                + "For a floating-rate loan the schedule is recalculated when the rate changes, so future instalments "
                + "may differ from those shown. " + contactLine));
    }

    // ---------------------------------------------------------------------------------------------
    // Customer-facing wording
    // ---------------------------------------------------------------------------------------------

    static String statusLabel(LoanAccount.AccountStatus status) {
        return switch (status) {
            case PENDING_DISBURSEMENT -> "Sanctioned, awaiting disbursement";
            case ACTIVE -> "Active";
            case NPA_SMA0, NPA_SMA1, NPA_SMA2, NPA_SUBSTANDARD, NPA_DOUBTFUL, NPA_LOSS ->
                    "Payment overdue, please contact us";
            case CLOSED -> "Closed";
            case FORECLOSED -> "Closed (paid off early)";
            case WRITTEN_OFF -> "Closed, please contact us";
        };
    }

    static String accountTypeLabel(LoanAccount.AccountType type) {
        return type == LoanAccount.AccountType.MORTGAGE ? "Mortgage loan (secured)" : "Personal loan (unsecured)";
    }

    static String dayCountLabel(LoanAccount.DayCountConvention convention) {
        if (convention == null) {
            return "N/A";
        }
        return switch (convention) {
            case ACT_365 -> "Actual/365";
            case ACT_ACT -> "Actual/Actual";
            case THIRTY_360 -> "30/360";
        };
    }

    static String installmentLabel(AmortizationScheduleEntry.InstallmentStatus status) {
        return switch (status) {
            case PAID -> "Paid";
            case PENDING -> "Upcoming";
            case OVERDUE -> "Overdue";
        };
    }

    private static Color installmentColor(AmortizationScheduleEntry.InstallmentStatus status) {
        return switch (status) {
            case PAID -> GOOD;
            case PENDING -> MUTED;
            case OVERDUE -> BAD;
        };
    }

    static String transactionLabel(EmiTransaction.TransactionType type) {
        return switch (type) {
            case EMI -> "EMI payment";
            case PART_PAYMENT -> "Part-payment";
            case FORECLOSURE -> "Loan closure payment";
            case CHARGE -> "Fee or charge";
        };
    }

    static String channelLabel(EmiTransaction.PaymentChannel channel) {
        return switch (channel) {
            case NACH -> "Auto-debit (NACH)";
            case MANUAL -> "Branch";
            case ONLINE -> "Online";
        };
    }

    private static String rateText(LoanAccount a, Fmt fmt) {
        String rate = fmt.plain(a.getInterestRate()) + "% a year";
        if (a.getRateType() == LoanAccount.RateType.FLOATING) {
            String link = a.getBenchmarkRateRef() != null ? " linked to " + a.getBenchmarkRateRef() : "";
            String spread = a.getSpread() != null ? " + " + fmt.plain(a.getSpread()) + "%" : "";
            return rate + " (floating" + link + spread + ")";
        }
        return rate + " (fixed)";
    }

    private static String date(OffsetDateTime time) {
        return time.toLocalDate().format(DATE);
    }

    // ---------------------------------------------------------------------------------------------
    // Cell and font helpers
    // ---------------------------------------------------------------------------------------------

    private static Font font(float size, int style, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
    }

    private static Paragraph sectionTitle(String text) {
        Paragraph p = new Paragraph(text, font(11, Font.BOLD, BRAND));
        p.setSpacingBefore(4);
        p.setSpacingAfter(4);
        p.setKeepTogether(true);
        return p;
    }

    private static Paragraph note(String text) {
        Paragraph p = new Paragraph(text, font(8.5f, Font.NORMAL, MUTED));
        p.setSpacingAfter(6);
        return p;
    }

    private static PdfPCell noBorder() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static void detail(PdfPTable table, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label, font(8.5f, Font.NORMAL, MUTED)));
        l.setBorder(Rectangle.BOTTOM);
        l.setBorderColor(RULE);
        l.setPaddingTop(4);
        l.setPaddingBottom(4);
        table.addCell(l);
        PdfPCell v = new PdfPCell(new Phrase(value, font(9, Font.BOLD, INK)));
        v.setBorder(Rectangle.BOTTOM);
        v.setBorderColor(RULE);
        v.setPaddingTop(4);
        v.setPaddingBottom(4);
        table.addCell(v);
    }

    private static void keyFigure(PdfPTable table, String label, String value, Color color) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setBackgroundColor(ZEBRA);
        cell.setPadding(7);
        cell.addElement(new Paragraph(label, font(8, Font.NORMAL, MUTED)));
        cell.addElement(new Paragraph(value, font(10.5f, Font.BOLD, color)));
        table.addCell(cell);
    }

    private static void headerCell(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(8, Font.BOLD, Color.WHITE)));
        cell.setBackgroundColor(BRAND);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(align);
        cell.setPaddingTop(5);
        cell.setPaddingBottom(5);
        table.addCell(cell);
    }

    private static void bodyCell(PdfPTable table, String text, int align, Color background, Color color, int style) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(8, style, color)));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(align);
        cell.setPaddingTop(3);
        cell.setPaddingBottom(3);
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        table.addCell(cell);
    }

    private static void totalCell(PdfPTable table, String text, int colspan, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(8, Font.BOLD, INK)));
        cell.setColspan(colspan);
        cell.setHorizontalAlignment(align);
        cell.setBorder(Rectangle.TOP);
        cell.setBorderColor(INK);
        cell.setPaddingTop(4);
        cell.setPaddingBottom(4);
        table.addCell(cell);
    }

    /** Number formatting in Indian digit grouping (12,34,567.00). One instance per render: NumberFormat is not thread-safe. */
    private final class Fmt {
        private final NumberFormat grouped = NumberFormat.getNumberInstance(Locale.of("en", "IN"));
        private final NumberFormat plain = NumberFormat.getNumberInstance(Locale.ENGLISH);

        Fmt() {
            grouped.setMinimumFractionDigits(2);
            grouped.setMaximumFractionDigits(2);
            plain.setMinimumFractionDigits(2);
            plain.setMaximumFractionDigits(4);
            plain.setGroupingUsed(false);
        }

        String number(BigDecimal value) {
            return grouped.format(value);
        }

        String money(BigDecimal value) {
            return currency + " " + grouped.format(value);
        }

        String plain(BigDecimal value) {
            return plain.format(value);
        }
    }

    /** Footer on every page: statement identity on the left, "Page n of m" on the right. */
    private static final class FooterEvent extends PdfPageEventHelper {
        private static final float SIZE = 7.5f;
        private final String left;
        private PdfTemplate total;
        private BaseFont baseFont;

        FooterEvent(String left) {
            this.left = left;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(30, 12);
            try {
                baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (DocumentException | IOException e) {
                throw new IllegalStateException("Could not load the footer font", e);
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float y = document.bottom() - 22;
            cb.saveState();
            cb.setLineWidth(0.5f);
            cb.setColorStroke(RULE);
            cb.moveTo(document.left(), y + 12);
            cb.lineTo(document.right(), y + 12);
            cb.stroke();

            cb.beginText();
            cb.setFontAndSize(baseFont, SIZE);
            cb.setColorFill(MUTED);
            cb.setTextMatrix(document.left(), y);
            cb.showText(left);
            cb.endText();

            String pageText = "Page " + writer.getPageNumber() + " of ";
            float width = baseFont.getWidthPoint(pageText, SIZE);
            cb.beginText();
            cb.setFontAndSize(baseFont, SIZE);
            cb.setColorFill(MUTED);
            cb.setTextMatrix(document.right() - width - 12, y);
            cb.showText(pageText);
            cb.endText();
            cb.addTemplate(total, document.right() - 12, y);
            cb.restoreState();
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(baseFont, SIZE);
            total.setColorFill(MUTED);
            total.setTextMatrix(0, 0);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }
    }
}
