package com.inventory.preview;

import com.inventory.model.InventorySnapshot;
import com.inventory.model.ProductCategory;
import com.inventory.model.procurement.OrderStatus;
import com.inventory.model.procurement.OrderAction;
import com.inventory.model.procurement.SupplierOffer;
import com.inventory.model.procurement.PurchaseOrder;
import com.inventory.security.Permission;
import com.inventory.security.User;
import com.inventory.service.OperationsService;
import com.inventory.service.analytics.AnalyticsReport;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import static com.inventory.preview.InventoryPage.html;

/** Rendering only: all numbers and transitions are provided by application services. */
final class OperationsPage {
    private final OperationsService operations;
    private final com.inventory.service.BusinessService business;

    OperationsPage(OperationsService operations,com.inventory.service.BusinessService business) { this.operations = operations; this.business=business; }

    record View(String hero, String content) { }

    View render(User actor, Map<String, String> query, String csrf, Map<String, String> submitted, String action) {
        if (!actor.role().allows(Permission.VIEW_ANALYTICS)) return new View("", "");
        LocalDate to = LocalDate.parse(query.getOrDefault("to", operations.today().toString()));
        LocalDate from = LocalDate.parse(query.getOrDefault("from", to.minusDays(29).toString()));
        var dashboard = operations.dashboard(actor, from, to);
        var report = dashboard.analytics();
        var snapshot = dashboard.snapshot();
        StringBuilder out = new StringBuilder(ManagementDashboard.render(actor,report,snapshot,business==null?java.util.List.of():business.invoices(actor),operations.today()));
        out.append("<section id='analytics' data-view='analytics' class='panel ops-panel workspace-view'><div class='panel-title'><h2>Sales &amp; inventory analytics</h2><p>Recorded unit movements in ")
                .append(report.zone()).append(". Current stock value uses today's selling prices, not acquisition cost.</p></div>");
        out.append("<form class='date-filter' method='get' action='/app#analytics'>")
                .append(input("From", "from", "date", from.toString(), ""))
                .append(input("Through", "to", "date", to.toString(), ""))
                .append("<button type='submit'>Update analytics</button></form><div class='analytics-metrics'>")
                .append(metric("Units sold", String.valueOf(report.soldUnits()), "Selected period"))
                .append(metric("Units received", String.valueOf(report.receivedUnits()), "Selected period"))
                .append(metric("Current retail stock value", report.currentRetailValue().toPlainString(), "Not revenue, profit, or cost value"))
                .append(metric("Open purchase orders", String.valueOf(snapshot.orders().stream().filter(order -> order.status().isOpen()).count()), "All current commitments"))
                .append("</div>").append(chart(report));
        out.append("<div class='table-wrap'><table><thead><tr><th>Product / best sellers first</th><th>Sold units</th><th>Received units</th><th>Days of cover</th></tr></thead><tbody>");
        for (var performance : report.products()) {
            out.append("<tr><td>").append(html(performance.stock().product().name())).append("</td><td>").append(performance.sold())
                    .append("</td><td>").append(performance.received()).append("</td><td>")
                    .append(performance.daysCover() == null ? (performance.sold() == 0 ? "No sales in period" : "Insufficient history (<7 days)") : performance.daysCover().toPlainString() + " days")
                    .append("</td></tr>");
        }
        out.append("</tbody></table></div></section><section id='replenishment' data-view='replenishment' class='panel ops-panel workspace-view'><div class='panel-title'><h2>Low stock &amp; replenishment</h2><p>Review a suggestion, choose a supplier, and create a purchase draft. An order does not change physical stock until receipt.</p></div><div class='restock-grid'>");
        if (report.restock().isEmpty()) out.append("<p class='empty'>No products need replenishment under this date range and current thresholds.</p>");
        if(actor.role()==com.inventory.security.Role.ADMIN) {
            for(var request:snapshot.orders().reversed()) if(request.status()==OrderStatus.PENDING_APPROVAL) {
                out.append("<article class='restock-card'><h3>Reorder request · ").append(html(request.sku())).append("</h3><p>").append(request.quantity()).append(" units · ").append(html(request.supplierName())).append("</p><p>").append(html(request.note())).append("</p>");
                if(!request.createdBy().equals(actor.id())) out.append(actionForm(request,OrderAction.APPROVE,"Approve reorder request",csrf)).append(actionForm(request,OrderAction.REJECT,"Reject reorder request",csrf));
                out.append("</article>");
            }
        }
        for (var suggestion : report.restock()) out.append(restockCard(actor, suggestion, snapshot, csrf, action.equals("/purchase/create") ? submitted : Map.of()));
        out.append("</div></section>").append(orders(actor, snapshot, query, csrf, submitted, action));
        out.append(quotes(snapshot, csrf));
        return new View(DashboardPresentation.hero(report, snapshot, operations.today()), out.toString());
    }

    private String restockCard(User actor, AnalyticsReport.RestockSuggestion suggestion, InventorySnapshot snapshot, String csrf, Map<String, String> submitted) {
        String sku = suggestion.stock().product().sku();
        Map<String, String> fields = snapshot.offers().stream().anyMatch(offer -> offer.sku().equals(sku) && offer.id().toString().equals(submitted.get("offerId"))) ? submitted : Map.of();
        StringBuilder out = new StringBuilder("<article class='restock-card'><h3>" + html(suggestion.stock().product().name()) + "</h3><small>" + html(sku) + "</small>");
        out.append("<p><strong>").append(suggestion.stock().quantity()).append(" on hand</strong> · Trigger ").append(suggestion.trigger())
                .append(" · Approved inbound ").append(suggestion.approvedInbound()).append("</p><details><summary>Why this suggestion?</summary><p>")
                .append(html(suggestion.explanation())).append("</p></details>");
        var open = snapshot.orders().stream().filter(order -> order.sku().equals(sku) && order.status().isOpen()).findFirst();
        if (open.isPresent()) {
            PurchaseOrder order = open.get();
            out.append("<p class='order-pending'>").append(html(order.number())).append(" · ").append(order.status())
                    .append(" · ").append(order.outstanding()).append(" units outstanding</p><a class='button' href='/app?order=")
                    .append(order.id()).append("#purchase-orders'>Continue reorder</a>");
            if(actor.role()==com.inventory.security.Role.ADMIN && order.status()==OrderStatus.PENDING_APPROVAL && !order.createdBy().equals(actor.id())) out.append(actionForm(order,OrderAction.APPROVE,"Approve reorder request",csrf)).append(actionForm(order,OrderAction.REJECT,"Reject reorder request",csrf));
            out.append("<p class='muted'>A reorder already exists for this product. Continue it to avoid ordering the same stock twice.</p>");
            if(order.status()==OrderStatus.DRAFT) out.append(actionForm(order,OrderAction.SUBMIT,"Send reorder request to Admin",csrf));
            if(actor.role()==com.inventory.security.Role.MANAGER && order.status()==OrderStatus.PENDING_APPROVAL) out.append("<p class='message success'>Request sent to Admin. Awaiting review.</p>");
        } else if (suggestion.offer() == null) {
            out.append("<p>No supplier quote is configured.</p><a class='button' href='#supplier-quotes'>Add supplier quote</a>");
        } else if (suggestion.suggestedQuantity() == 0) {
            out.append("<p>Review quantity limits before placing a new order.</p>");
        } else if(actor.role()==com.inventory.security.Role.MANAGER) {
            out.append("<form method='post' action='/purchase/create'>").append(hidden("csrf",csrf)).append(hidden("requestId",UUID.randomUUID().toString()))
                .append(hidden("offerId",suggestion.offer().id().toString())).append(hidden("quantity",String.valueOf(suggestion.suggestedQuantity())))
                .append(input("Request reason","note","text","Low stock: "+sku,"maxlength='2000'"))
                .append("<p>Suggested quantity: ").append(suggestion.suggestedQuantity()).append(" units. Admin reviews the reorder request.</p><button>Send reorder request to Admin</button></form>");
        } else {
            out.append("<form method='post' action='/purchase/create'>").append(hidden("csrf", csrf)).append(hidden("requestId", fields.getOrDefault("requestId", UUID.randomUUID().toString())))
                    .append("<label>Supplier quote<select required name='offerId'>");
            snapshot.offers().stream().filter(offer -> offer.sku().equals(sku)).sorted(Comparator.comparing(SupplierOffer::unitCost)).forEach(offer ->
                    out.append("<option value='").append(offer.id()).append("'").append(offer.id().toString().equals(fields.getOrDefault("offerId", suggestion.offer().id().toString())) ? " selected" : "")
                            .append('>').append(html(offer.supplierName())).append(" · ").append(offer.unitCost()).append(" / unit · ")
                            .append(offer.leadDays()).append(" days · min ").append(offer.minimumQuantity()).append("</option>"));
            out.append("</select></label>").append(input("Order quantity", "quantity", "number", fields.getOrDefault("quantity", String.valueOf(suggestion.suggestedQuantity())), "min='1' max='2147483647' step='1'"))
                    .append(input("Purchase reason", "note", "text", fields.getOrDefault("note", "Replenish " + sku), "maxlength='2000'"))
                    .append("<small>Suggestion uses the lowest unit-cost quote. Review quantity if you choose another supplier.</small><button type='submit'>"+(actor.role()==com.inventory.security.Role.MANAGER?"Send reorder request to Admin":"Reorder stock")+"</button></form>");
        }
        return out.append("</article>").toString();
    }

    private String orders(User actor, InventorySnapshot snapshot, Map<String, String> query, String csrf, Map<String, String> submitted, String action) {
        boolean deliveryView=actor.role()==com.inventory.security.Role.ADMIN;
        StringBuilder out = new StringBuilder("<section id='purchase-orders' data-view='purchase-orders' class='panel ops-panel workspace-view'><div class='panel-title'><h2>"+(deliveryView?"Deliveries":"Purchase orders")+"</h2><p>"+(deliveryView?"Track suppliers, expected dates and received quantities. Select an order for delivery details.":"Review purchase lines, approvals and deliveries.")+"</p></div><div class='table-wrap'><table><thead><tr><th>Order / product</th><th>Supplier</th><th>Status</th><th>Received / ordered</th><th>Total cost</th><th>Estimated due</th></tr></thead><tbody>");
        for (var order : snapshot.orders().reversed()) {
            out.append("<tr><td><a href='/app?order=").append(order.id()).append("#purchase-orders'>").append(order.number()).append("</a><small>")
                    .append(html(order.sku())).append("</small></td><td>").append(html(order.supplierName())).append("</td><td>").append(order.status())
                    .append("</td><td>").append(order.receivedQuantity()).append(" / ").append(order.quantity()).append("</td><td>")
                    .append(order.totalCost().toPlainString()).append("</td><td>").append(order.expectedOn())
                    .append(order.status().isOpen() && order.expectedOn().isBefore(operations.today()) ? " · overdue estimate" : "").append("</td></tr>");
        }
        if (snapshot.orders().isEmpty()) out.append("<tr><td colspan='6' class='empty'>No purchase orders recorded.</td></tr>");
        out.append("</tbody></table></div>");
        PurchaseOrder selected = query.containsKey("order")
                ? snapshot.orders().stream().filter(order -> order.id().toString().equals(query.get("order"))).findFirst().orElse(null)
                : snapshot.orders().reversed().stream().findFirst().orElse(null);
        if (selected != null) out.append(orderDetail(actor, selected, snapshot, csrf, submitted, action));
        return out.append("</section>").toString();
    }

    private String orderDetail(User actor, PurchaseOrder order, InventorySnapshot snapshot, String csrf, Map<String, String> submitted, String action) {
        Map<String, String> fields = order.id().toString().equals(submitted.get("orderId")) && action.equals("/purchase/receive") ? submitted : Map.of();
        StringBuilder out = new StringBuilder("<div class='order-detail'><h3>" + order.number() + " · " + html(order.sku()) + "</h3><p>Created by "
                + html(DemoData.actorName(order.createdBy())) + ". " + html(order.note()) + "</p><div class='order-actions'>");
        boolean deliveryView=actor.role()==com.inventory.security.Role.ADMIN;
        if (order.status() == OrderStatus.DRAFT) {
            out.append("<p>This reorder is still a draft. Submit it to start processing; stock changes when you receive the delivery.</p>");
            out.append(actionForm(order, OrderAction.SUBMIT, "Submit reorder", csrf));
        }
        if(order.status()==OrderStatus.RECEIVED) out.append("<p class='message success'>Delivery complete. All ordered units have been added to stock.</p>");
        if(order.status()==OrderStatus.CANCELLED || order.status()==OrderStatus.REJECTED) out.append("<p>This order is closed. Open <a href='#replenishment'>Replenishment</a> to reorder stock.</p>");
        if (order.status() == OrderStatus.PENDING_APPROVAL) {
            if (order.createdBy().equals(actor.id())) out.append("<p>Another Manager or Admin must review this order.</p>");
            else out.append(actionForm(order, OrderAction.APPROVE, "Approve order", csrf)).append(actionForm(order, OrderAction.REJECT, "Reject order", csrf));
        }
        if (order.status().isOpen() && order.receivedQuantity() == 0) out.append(actionForm(order, OrderAction.CANCEL, "Cancel order", csrf));
        out.append("<p>Supplier: ").append(html(order.supplierName())).append(" · Expected: ").append(order.expectedOn()).append(" · Received: ").append(order.receivedQuantity()).append(" · Remaining: ").append(order.outstanding()).append("</p>");
        if (order.status() == OrderStatus.APPROVED || order.status() == OrderStatus.PARTIALLY_RECEIVED) {
            out.append("<form method='post' action='/purchase/receive'>").append(hidden("csrf", csrf)).append(hidden("orderId", order.id().toString()))
                    .append(hidden("requestId", fields.getOrDefault("requestId", UUID.randomUUID().toString())))
                    .append(input("Received quantity", "quantity", "number", fields.getOrDefault("quantity", String.valueOf(order.outstanding())), "min='1' max='" + order.outstanding() + "' step='1'"))
                    .append(input("Receipt reason", "note", "text", fields.getOrDefault("note", "Delivery for " + order.number()), "maxlength='2000'"))
                    .append("<button type='submit'>Receive delivery</button></form>");
        }
        out.append("</div><details><summary>Order event history</summary><ul>");
        snapshot.events().stream().filter(event -> event.orderId().equals(order.id())).forEach(event -> out.append("<li><strong>")
                .append(html(event.action())).append("</strong><p>").append(html(event.note())).append("</p><small>")
                .append(event.occurredAt()).append(" · ").append(html(DemoData.actorName(event.actorId()))).append("</small></li>"));
        return out.append("</ul></details></div>").toString();
    }

    private String actionForm(PurchaseOrder order, OrderAction action, String label, String csrf) {
        return "<form method='post' action='/purchase/action'>" + hidden("csrf", csrf) + hidden("orderId", order.id().toString())
                + hidden("action", action.name()) + input(label + " reason", "note", "text", "", "maxlength='2000'")
                + "<button class='secondary' type='submit'>" + label + "</button></form>";
    }

    private String quotes(InventorySnapshot snapshot, String csrf) {
        StringBuilder out = new StringBuilder("<section id='supplier-quotes' data-view='supplier-quotes' class='panel ops-panel workspace-view'><details open><summary>Supplier quotes &amp; lead times</summary><p>Quotes are local supplier references. Orders keep a snapshot of the chosen price.</p><div class='table-wrap'><table><thead><tr><th>Product</th><th>Supplier</th><th>Unit cost</th><th>Lead days</th><th>Minimum units</th></tr></thead><tbody>");
        snapshot.offers().forEach(offer -> out.append("<tr><td>").append(html(offer.sku())).append("</td><td>").append(html(offer.supplierName()))
                .append("</td><td>").append(offer.unitCost()).append("</td><td>").append(offer.leadDays()).append("</td><td>").append(offer.minimumQuantity()).append("</td></tr>"));
        out.append("</tbody></table></div><form class='quote-form' method='post' action='/suppliers/offers/create'>")
                .append(hidden("csrf", csrf)).append(hidden("requestId", UUID.randomUUID().toString())).append("<div class='form-grid'><label>Quote product<select required name='sku'>");
        snapshot.stock().stream().filter(stock -> stock.product().active() && stock.product().details().category() != ProductCategory.LEGACY)
                .forEach(stock -> out.append("<option value='").append(html(stock.product().sku())).append("'>").append(html(stock.product().name())).append("</option>"));
        out.append("</select></label>").append(input("Supplier name", "supplierName", "text", "", "maxlength='255'"))
                .append(input("Purchase unit cost", "unitCost", "number", "", "min='0' step='0.01'"))
                .append(input("Lead time (days)", "leadDays", "number", "4", "min='0' max='365' step='1'"))
                .append(input("Minimum order units", "minimumQuantity", "number", "1", "min='1' max='2147483647' step='1'"))
                .append("</div><button type='submit'>Save supplier quote</button></form></details></section>");
        return out.toString();
    }

    private String chart(AnalyticsReport report) {
        long max = report.daily().stream().mapToLong(day -> Math.max(day.sold(), day.received())).max().orElse(0);
        int count = report.daily().size();
        double step = 880.0 / count;
        StringBuilder svg = new StringBuilder("<div class='trend-chart'><h3>Daily unit movements</h3><p class='muted'>Lime: sold · Sage: received. Expand the table for daily quantities.</p><svg role='img' aria-label='Daily units sold and received' viewBox='0 0 900 180'>");
        for (int index = 0; index < count; index++) {
            var day = report.daily().get(index);
            double soldHeight = max == 0 ? 0 : day.sold() * 140.0 / max;
            double receivedHeight = max == 0 ? 0 : day.received() * 140.0 / max;
            double x = 10 + index * step;
            svg.append(String.format(java.util.Locale.ROOT,
                    "<g><title>%s: sold %d, received %d</title><rect x='%.2f' y='%.2f' width='%.2f' height='%.2f' fill='#d9ff57'/><rect x='%.2f' y='%.2f' width='%.2f' height='%.2f' fill='#7c8b62'/></g>",
                    day.date(), day.sold(), day.received(), x, 150 - soldHeight, step * .4, soldHeight, x + step * .45, 150 - receivedHeight, step * .4, receivedHeight));
        }
        svg.append("<line x1='10' y1='150' x2='890' y2='150' stroke='#cdd8d0'/></svg><div class='chart-range'><span>")
                .append(report.from()).append("</span><span>Peak daily units: ").append(max).append("</span><span>").append(report.to())
                .append("</span></div><details><summary>Daily movement data</summary><div class='table-wrap'><table><thead><tr><th>Date</th><th>Sold</th><th>Received</th></tr></thead><tbody>");
        report.daily().forEach(day -> svg.append("<tr><td>").append(day.date()).append("</td><td>").append(day.sold()).append("</td><td>").append(day.received()).append("</td></tr>"));
        return svg.append("</tbody></table></div></details></div>").toString();
    }

    static String demoActors(User actor, String csrf) {
        StringBuilder form = new StringBuilder("<form class='demo-actors' method='post' action='/demo/actor'>" + hidden("csrf", csrf) + "<label>Demo identity (not a login)<select name='actor'>");
        for (String key : java.util.List.of("manager", "approver", "cashier")) {
            var value = DemoData.actor(key);
            form.append("<option value='").append(key).append("'").append(actor.id().equals(value.id()) ? " selected" : "")
                    .append('>').append(html(value.username())).append(" · ").append(value.role()).append("</option>");
        }
        return form.append("</select></label><button class='secondary' type='submit'>Use demo identity</button><small>Switch to demo-approver for independent purchase approval.</small></form>").toString();
    }

    private static String metric(String label, String value, String note) { return "<div><span>" + label + "</span><strong>" + value + "</strong><small>" + note + "</small></div>"; }
    private static String hidden(String name, String value) { return "<input type='hidden' name='" + name + "' value='" + html(value) + "'>"; }
    private static String input(String label, String name, String type, String value, String attributes) {
        if(type.equals("number")) return NumericFields.render(label,name,value,attributes);
        return "<label>" + label + "<input required type='" + type + "' name='" + name + "' value='" + html(value) + "' " + attributes + "></label>";
    }
}
