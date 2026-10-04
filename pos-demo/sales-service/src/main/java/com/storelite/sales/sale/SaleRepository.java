package com.storelite.sales.sale;

import com.storelite.sales.api.model.TenderType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SaleRepository {

    public record Summary(long count, long totalCents) {
    }

    private record Header(UUID id, OffsetDateTime createdAt, String tenderType, long totalCents) {
    }

    private record LineWithSale(UUID saleId, String sku, String name, long unitPriceCents, int quantity) {
    }

    private final JdbcClient jdbc;

    public SaleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SaleRow sale) {
        jdbc.sql("INSERT INTO sales.sale (id, created_at, tender_type, total_cents) VALUES (?, ?, ?, ?)")
                .params(sale.id(), sale.createdAt(), sale.tenderType().getValue(), sale.totalCents())
                .update();
        int lineNo = 0;
        for (SaleLineRow line : sale.lines()) {
            jdbc.sql("""
                    INSERT INTO sales.sale_line (sale_id, line_no, sku, name, unit_price_cents, quantity)
                    VALUES (?, ?, ?, ?, ?, ?)""")
                    .params(sale.id(), ++lineNo, line.sku(), line.name(), line.unitPriceCents(), line.quantity())
                    .update();
        }
    }

    /** Sales in [from, to), newest first, with their lines. */
    public List<SaleRow> findBetween(OffsetDateTime from, OffsetDateTime to, int limit) {
        List<Header> headers = jdbc.sql("""
                SELECT id, created_at, tender_type, total_cents FROM sales.sale
                WHERE created_at >= :from AND created_at < :to
                ORDER BY created_at DESC LIMIT :limit""")
                .param("from", from).param("to", to).param("limit", limit)
                .query(Header.class)
                .list();
        if (headers.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<SaleLineRow>> linesBySale = jdbc.sql("""
                SELECT sale_id, sku, name, unit_price_cents, quantity FROM sales.sale_line
                WHERE sale_id IN (:ids) ORDER BY sale_id, line_no""")
                .param("ids", headers.stream().map(Header::id).toList())
                .query(LineWithSale.class)
                .list()   // not .stream(): that holds the connection open until the stream is closed
                .stream()
                .collect(Collectors.groupingBy(LineWithSale::saleId, Collectors.mapping(
                        l -> new SaleLineRow(l.sku(), l.name(), l.unitPriceCents(), l.quantity()),
                        Collectors.toList())));

        return headers.stream()
                .map(h -> new SaleRow(h.id(), h.createdAt(), TenderType.fromValue(h.tenderType()), h.totalCents(),
                        linesBySale.getOrDefault(h.id(), List.of())))
                .toList();
    }

    public Summary summarize(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                SELECT count(*) AS count, coalesce(sum(total_cents), 0) AS total_cents FROM sales.sale
                WHERE created_at >= ? AND created_at < ?""")
                .params(from, to)
                .query(Summary.class)
                .single();
    }
}
