package com.inventory.preview;

import com.inventory.model.*;
import com.inventory.security.User;
import com.inventory.security.Role;
import com.inventory.model.procurement.OrderStatus;
import com.inventory.service.analytics.AnalyticsReport;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.inventory.preview.InventoryPage.html;

/** Operational dashboard: every value is calculated from recorded data. */
final class ManagementDashboard {
    private static final String BOX="M3 7l9-4 9 4-9 4zM3 7v10l9 4 9-4V7M12 11v10";
    private static final String CART="M3 4h3l3 12h10l2-8H7M10 21h.01M18 21h.01";
    private static final String ORDER="M7 3h10v4H7zM7 5H4v16h16V5h-3M8 12h8M8 16h5";
    private static final String ALERT="m12 3 10 18H2zM12 9v5M12 17h.01";
    private static final String[] COLORS={"#d9ff57","#b4c477","#87945a","#65704b","#dedfd1","#9b9e89"};
    private ManagementDashboard() { }
    static String render(User actor, AnalyticsReport report,InventorySnapshot snapshot,List<Map<String,String>> invoices,LocalDate today) {
        long active=report.products().stream().filter(p->p.stock().product().active()).count();
        long units=report.products().stream().mapToLong(p->p.stock().quantity()).sum();
        long low=report.products().stream().filter(p->p.stock().isLowStock()).count();
        long empty=report.products().stream().filter(p->p.stock().product().active() && p.stock().quantity()==0).count();
        long pending=snapshot.orders().stream().filter(o->o.status()==OrderStatus.PENDING_APPROVAL).count();
        BigDecimal billed=BigDecimal.ZERO,todayBilled=BigDecimal.ZERO;
        for(var invoice:invoices) {
            LocalDate date=Instant.parse(invoice.get("created_at")).atZone(report.zone()).toLocalDate();
            BigDecimal value=new BigDecimal(invoice.get("total"));
            if(date.equals(today)) todayBilled=todayBilled.add(value);
            if(!date.isBefore(report.from()) && !date.isAfter(report.to())) billed=billed.add(value);
        }
        BigDecimal purchases=snapshot.orders().stream().filter(o->o.status().isOpen()).map(o->o.unitCost().multiply(BigDecimal.valueOf(o.outstanding()))).reduce(BigDecimal.ZERO,BigDecimal::add);
        StringBuilder out=new StringBuilder("<section id='dashboard' data-view='dashboard' class='workspace-view management-dashboard'><div class='dashboard-heading'><div><h1>Dashboard</h1><p>Your electronics inventory, sales and purchasing overview.</p></div></div>");
        out.append("<section class='panel quick-actions'><h2>Quick actions</h2><div class='quick-action-grid'>");
        out.append(action("management","Inventory",BOX));
        if (actor.role() == Role.ADMIN) {
            out.append(action("add-product","Add product",BOX))
                .append(action("purchase-batches","Create order",ORDER))
                .append(action("supplier-directory","Add supplier","M3 21V7l9-4 9 4v14M7 10h10M7 15h10"))
                .append(action("purchase-orders","Receive delivery","M3 7h12v11H3zM15 11h4l3 4v3h-7M7 21h.01M18 21h.01"));
        } else if (actor.role() == Role.MANAGER) {
            out.append(action("new-sale","New sale",CART));
        }
        out.append(action("replenishment","Stock alerts",ALERT))
            .append("</div></section><div class='dashboard-metrics'>")
            .append(metric("Today's billed sales",money(todayBilled),"Invoices issued today","sales",CART,false))
            .append(metric("Billed sales",money(billed),"Selected period · before refunds","sales",CART,false))
            .append(metric("Open purchase value",money(purchases),"Outstanding units on open orders","purchase-batches",ORDER,false))
            .append(metric("Active products",Long.toString(active),"Electronics catalogue","management",BOX,false))
            .append(metric("Low stock",Long.toString(low),"At or below reorder level","replenishment",ALERT,low>0))
            .append(metric("Out of stock",Long.toString(empty),"Active products with no units","management",BOX,empty>0))
            .append(metric("Awaiting approval",Long.toString(pending),"Purchase lines requiring review","purchase-orders",ORDER,pending>0))
            .append(metric("Units on hand",Long.toString(units),"Current electronics stock","management",BOX,false)).append("</div>");
        out.append("<div class='dashboard-charts'><section class='panel movement-overview'><div class='chart-heading'><div><h2>Sales &amp; receipts</h2><p>Recorded unit movements</p></div><div class='period-tabs'>")
            .append(period("Today",today,today,report)).append(period("Week",today.minusDays(6),today,report)).append(period("Month",today.minusDays(29),today,report))
            .append("</div></div><div class='chart-totals'><span><i></i>Sold <strong>").append(report.soldUnits()).append(" units</strong></span><span><i></i>Received <strong>").append(report.receivedUnits()).append(" units</strong></span></div>")
            .append(chart(report)).append("<div class='chart-range'><span>").append(report.from()).append("</span><span>").append(report.to()).append("</span></div></section>");
        out.append("<section class='panel category-overview'><h2>Inventory by category</h2><p>Electronics units on hand</p><div class='dashboard-category-body'><div class='donut'><svg viewBox='0 0 120 120' aria-hidden='true'><circle cx='60' cy='60' r='50' fill='none' stroke='#3c4134' stroke-width='12'/><g transform='rotate(-90 60 60)'>");
        double offset=0;int index=0;StringBuilder legend=new StringBuilder("<ul class='category-legend'>");
        for(var category:ProductCategory.electronics()) {
            long count=report.products().stream().filter(p->p.stock().product().details().category()==category).mapToLong(p->p.stock().quantity()).sum();
            double percent=units==0?0:count*100.0/units;String color=COLORS[index++];
            out.append(String.format(Locale.ROOT,"<circle cx='60' cy='60' r='50' fill='none' stroke='%s' stroke-width='12' pathLength='100' stroke-dasharray='%.4f %.4f' stroke-dashoffset='%.4f'/>",color,percent,100-percent,-offset));offset+=percent;
            legend.append("<li><i style='background:").append(color).append("'></i><span>").append(html(category.label())).append("</span><b>").append(count).append("</b></li>");
        }
        out.append("</g></svg><div><strong>").append(units).append("</strong><small>units total</small></div></div>").append(legend).append("</ul></div></section></div>");
        out.append("<section class='panel dashboard-stock-table'><div class='panel-title'><h2>Current stock</h2><a href='#management' class='lime-link'>View inventory "+UiIcons.ARROW_UP_RIGHT+"</a></div><div class='table-wrap'><table><thead><tr><th>Product</th><th>SKU</th><th>On hand</th><th>Reorder at</th><th>Status</th></tr></thead><tbody>");
        for(var p:report.products()) {
            var stock=p.stock();var product=stock.product();
            out.append("<tr><td><a href='/app?sku=").append(InventoryPage.url(product.sku())).append("#management'>").append(html(product.name())).append("</a></td><td>").append(html(product.sku())).append("</td><td>").append(stock.quantity()).append("</td><td>").append(product.reorderLevel()).append("</td><td><span class='badge ").append(stock.isLowStock()?"warning":"good").append("'>").append(!product.active()?"Inactive":stock.quantity()==0?"Out of stock":stock.isLowStock()?"Low stock":"In stock").append("</span></td></tr>");
        }
        return out.append("</tbody></table></div></section></section>").toString();
    }
    private static String icon(String path) {return "<svg viewBox='0 0 24 24' aria-hidden='true'><path d='"+path+"'/></svg>";}
    private static String action(String target,String label,String path) {return "<a href='#"+target+"'><span class='quick-icon'>"+icon(path)+"</span><span>"+label+"</span></a>";}
    private static String metric(String label,String value,String note,String target,String path,boolean alert) {return "<a class='panel dashboard-metric"+(alert?" needs-attention":"")+"' href='#"+target+"'><span class='metric-icon'>"+icon(path)+"</span><span class='metric-label'>"+label+"</span><strong>"+html(value)+"</strong><small>"+note+"</small></a>";}
    private static String money(BigDecimal value) {var format=java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));format.setMaximumFractionDigits(2);return "₹"+format.format(value);}
    private static String period(String label,LocalDate from,LocalDate to,AnalyticsReport r) {return "<a class='"+(from.equals(r.from())&&to.equals(r.to())?"selected":"")+"' href='/app?from="+from+"&amp;to="+to+"#dashboard'>"+label+"</a>";}
    private static String chart(AnalyticsReport report) {
        long max=Math.max(1,report.daily().stream().mapToLong(d->Math.max(d.sold(),d.received())).max().orElse(1));
        StringBuilder svg=new StringBuilder("<svg class='dashboard-unit-chart' viewBox='0 0 800 230' role='img' aria-label='Daily units sold and received'>");
        for(int i=0;i<4;i++) svg.append("<line x1='40' x2='780' y1='").append(20+i*60).append("' y2='").append(20+i*60).append("' stroke='#3b3e34' stroke-dasharray='4 6'/><text x='4' y='").append(24+i*60).append("' fill='#9a9e8e' font-size='11'>").append(Math.round(max*(3-i)/3.0)).append("</text>");
        int size=report.daily().size();double step=740.0/size;
        for(int i=0;i<size;i++) {var day=report.daily().get(i);double sold=day.sold()*180.0/max,received=day.received()*180.0/max;
            svg.append(String.format(Locale.ROOT,"<g><title>%s: %d sold, %d received</title><rect x='%.2f' y='%.2f' width='%.2f' height='%.2f' fill='#d9ff57' rx='2'/><rect x='%.2f' y='%.2f' width='%.2f' height='%.2f' fill='#78865c' rx='2'/></g>",day.date(),day.sold(),day.received(),40+i*step,200-sold,step*.34,sold,40+i*step+step*.4,200-received,step*.34,received));
        }
        return svg.append("</svg>").toString();
    }
}
