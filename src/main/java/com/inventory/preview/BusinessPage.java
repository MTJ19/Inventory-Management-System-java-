package com.inventory.preview;

import com.inventory.service.BusinessService;
import com.inventory.service.InventoryService;
import com.inventory.security.*;
import java.util.*;
import java.time.*;
import static com.inventory.preview.InventoryPage.html;

/** Forms for retail operations; all permissions and validation are enforced by services. */
final class BusinessPage {
    private final BusinessService service;
    private final InventoryService inventory;
    BusinessPage(InventoryService inventory) { this.inventory=inventory;this.service=inventory.business(); }
    String render(User actor,String csrf,Map<String,String> query,Map<String,String> submitted,String action) {
        if(service==null) return "";
        String options=inventory.listProducts(actor).stream().filter(s->s.product().active() && s.product().details().category()!=com.inventory.model.ProductCategory.LEGACY).map(s->"<option data-stock='"+s.quantity()+"' data-price='"+s.product().sellingPrice()+"' value='"+html(s.product().sku())+"'>"+html(s.product().sku()+" · "+s.product().name())+" ("+s.quantity()+" available)</option>").collect(java.util.stream.Collectors.joining());
        StringBuilder out=new StringBuilder(section("new-sale","New customer order","Choose products, check quantities, then place the order."));
        out.append("<div class='checkout-layout'><section class='panel product-picker' hidden id='product-picker'><h3>Choose products</h3><label>Find a product<input type='search' id='cart-search' placeholder='Search name or SKU' autocomplete='off'></label><div class='checkout-products'>");
        for(var stock:inventory.listProducts(actor)) {
            var product=stock.product();
            if(!product.active() || product.details().category()==com.inventory.model.ProductCategory.LEGACY) continue;
            out.append("<article class='checkout-product' data-search='").append(html(product.name()+" "+product.sku())).append("'><small>").append(html(product.category())).append("</small><h4>").append(html(product.name())).append("</h4><span class='muted'>").append(html(product.sku())).append("</span><strong>INR ").append(product.sellingPrice()).append("</strong><span>").append(stock.quantity()).append(" in stock</span><button type='button' class='add-to-cart' data-sku='").append(html(product.sku())).append("'").append(stock.quantity()==0?" disabled":"").append(">"
                ).append(stock.quantity()==0?"Out of stock":"+ Add to cart").append("</button></article>");
        }
        out.append("</div><p id='no-product-match' class='empty' hidden>No products match. Try a name or SKU.</p></section><section class='panel cart-panel'><h3>Customer cart</h3>").append(form("checkout",csrf))
            .append(input("Customer name / reference","customer","text","Walk-in customer","maxlength='255'"))
            .append("<p id='empty-cart' class='empty' hidden>Your cart is empty. Choose a product to start.</p><p id='cart-status' role='status' aria-live='polite'></p>")
            .append("<div id='sale-lines' data-restored='").append(action.equals("/business/checkout")).append("'><div class='sale-line'><label>Product<select required name='sku0'>").append(options).append("</select></label>")
            .append(input("Quantity","qty0","number","1","min='1' max='2147483647'"))
            .append("<button type='button' class='secondary remove-line' aria-label='Remove invoice line'>Remove</button></div></div><button class='secondary' id='add-sale-line' type='button'>Add another product</button>")
            .append("<details class='cart-discount'><summary>Add a discount (optional)</summary>").append(input("Invoice discount (INR)","discount","number","0.00","min='0' step='0.01'")).append("</details>")
            .append("<div id='sale-preview' class='sale-preview' aria-live='polite'></div>")
            .append("<p class='muted'>Check the items and quantities. Saving records the sale and updates stock.</p><button id='save-order'>Place order</button></form></section></div></section>")
            .append(section("sales","Customer invoices","View customer invoices and print a copy."))
            .append("<section class='panel' id='order-history'><div class='panel-title'><div><h3>Customer invoices</h3><p>Select an invoice to view or print it.</p></div></div><div class='table-wrap'><table><thead><tr><th>Invoice</th><th>Customer</th><th>Total (INR)</th><th>Date</th></tr></thead><tbody>");
        var invoices=service.invoices(actor);
        for(var invoice:invoices) out.append("<tr><td><a href='/app?invoice=").append(invoice.get("id")).append("#invoice-document'>").append(number(invoice)).append("</a></td><td>").append(html(invoice.get("customer"))).append("</td><td>").append(invoice.get("total")).append("</td><td>").append(date(invoice.get("created_at"))).append("</td></tr>");
        out.append("</tbody></table></div>").append(invoices.isEmpty()?"<p class='empty'>No orders yet. Your saved orders will appear here.</p>":"").append("</section>");
        String selected=query.getOrDefault("invoice",invoices.isEmpty()?"":invoices.getFirst().get("id"));
        var invoice=invoices.stream().filter(r->r.get("id").equals(selected)).findFirst().orElse(null);
        if(invoice!=null) {
            var lines=service.lines(actor,UUID.fromString(selected));
            out.append("<section class='panel invoice-document' id='invoice-document'><div class='panel-title'><div><small>VOLTEDGE ELECTRONICS</small><h2>").append(number(invoice)).append("</h2><p>").append(html(invoice.get("customer"))).append(" · ").append(date(invoice.get("created_at"))).append("</p></div><button type='button' id='print-invoice' class='secondary'>Print invoice</button></div><table><thead><tr><th>Product</th><th>Quantity</th><th>Unit price</th><th>Amount</th></tr></thead><tbody>");
            for(var line:lines) out.append("<tr><td>").append(html(line.get("product_name"))).append("<small>").append(html(line.get("sku"))).append("</small></td><td>").append(line.get("quantity")).append("</td><td>").append(line.get("unit_price")).append("</td><td>").append(new java.math.BigDecimal(line.get("unit_price")).multiply(new java.math.BigDecimal(line.get("quantity")))).append("</td></tr>");
            out.append("</tbody></table>");
            out.append("<div class='invoice-totals'><p>Subtotal <strong>INR ").append(invoice.get("subtotal")).append("</strong></p><p>Discount <strong>INR ").append(invoice.get("discount")).append("</strong></p><p>Total <strong>INR ").append(invoice.get("total")).append("</strong></p></div><p class='muted'>Sales invoice · Payment collection is recorded separately. No tax calculation is applied.</p></section>");
        }
        out.append("</section>");
        if(actor.role().allows(Permission.MANAGE_PRODUCTS)) {
            out.append(section("stocktaking","Stocktaking","Submit physical counts for independent review. Stock changes only after approval."))
                .append("<div class='business-grid'><section class='panel'><h3>New count</h3>").append(form("count",csrf)).append(product(options))
                .append(input("Physical quantity","counted","number","0","min='0'"))
                .append(input("Count reference / reason","reason","text","","maxlength='2000'"))
                .append("<button>Submit count</button></form></section><section class='panel'><h3>Count register</h3>");
            var counts=service.counts(actor);
            if(counts.isEmpty()) out.append("<div class='empty-state'><h3>No stock counts recorded</h3><p>Submit a physical count to start an independent review.</p></div>");
            for(var count:counts) {
                out.append("<div class='register-row'><strong>").append(html(count.get("sku"))).append("</strong><p>Recorded ").append(count.get("expected")).append(" · Counted ").append(count.get("counted")).append(" · ").append(html(count.get("status"))).append("</p><p>").append(html(count.get("reason"))).append("</p>");
                if(count.get("status").equals("PENDING") && !count.get("created_by").equals(actor.id().toString())) out.append(form("count-review",csrf)).append(hidden("countId",count.get("id"))).append("<button name='decision' value='approve'>Approve adjustment</button> <button name='decision' value='reject' class='secondary'>Reject</button></form>");
                out.append("</div>");
            }
            out.append("</section></div></section>");
        }
        if(actor.role().allows(Permission.MANAGE_PURCHASES)) {
            out.append(section("purchase-batches","Purchase orders","Order several products from one supplier. Submit and review all lines together."));
            out.append("<section class='panel'><h3>New purchase order</h3>").append(form("purchase-batch",csrf));
            var offers=service.offers(actor);
            for(int i=0;i<5;i++) {
                out.append("<div class='purchase-line'><label>Product quote<select name='offer").append(i).append("'><option value=''>Select a product (optional line)</option>");
                for(var offer:offers) out.append("<option value='").append(offer.get("id")).append("'>").append(html(offer.get("supplier_name")+" · "+offer.get("sku")+" · INR "+offer.get("unit_cost"))).append("</option>");
                out.append("</select></label>").append(input("Quantity","quantity"+i,"number","1","min='1'")).append("</div>");
            }
            out.append(input("Purchase reason","note","text","","maxlength='2000'")).append("<button>Create draft order</button></form></section>");
            var purchaseLines=service.purchaseLines(actor);
            var groups=purchaseLines.stream().collect(java.util.stream.Collectors.groupingBy(r->r.get("group_id"),LinkedHashMap::new,java.util.stream.Collectors.toList()));
            for(var group:groups.entrySet()) {
                var first=group.getValue().getFirst();
                out.append("<section class='panel'><h3>PO-").append(group.getKey().substring(0,8).toUpperCase(Locale.ROOT)).append(" · ").append(html(first.get("supplier_name"))).append("</h3><table><thead><tr><th>Product</th><th>Ordered</th><th>Received</th><th>Unit cost</th><th>Status</th><th>Delivery</th></tr></thead><tbody>");
                for(var line:group.getValue()) out.append("<tr><td>").append(html(line.get("sku"))).append("</td><td>").append(line.get("quantity")).append("</td><td>").append(line.get("received_quantity")).append("</td><td>").append(line.get("unit_cost")).append("</td><td>").append(html(line.get("status"))).append("</td><td><a href='/app?order=").append(line.get("id")).append("#purchase-orders'>View / receive</a></td></tr>");
                out.append("</tbody></table>");
                boolean draft=group.getValue().stream().allMatch(r->r.get("status").equals("DRAFT"));
                boolean pending=group.getValue().stream().allMatch(r->r.get("status").equals("PENDING_APPROVAL"));
                boolean own=first.get("created_by").equals(actor.id().toString());
                if(draft || pending && !own) {
                    out.append(form("purchase-review",csrf)).append(hidden("groupId",group.getKey())).append(input("Review / submission reason","note","text","","maxlength='2000'"));
                    if(draft) out.append("<button name='action' value='SUBMIT'>Submit all lines</button>");
                    else out.append("<div><button name='action' value='APPROVE'>Approve all lines</button> <button class='secondary' name='action' value='REJECT'>Reject all lines</button></div>");
                    out.append("</form>");
                }
                out.append("</section>");
            }
            out.append("</section>");
            out.append(section("supplier-directory","Supplier directory","Contact records and current quotes. Existing orders retain their agreed prices."));
            out.append("<section class='panel'><h3>Add or update supplier</h3>").append(form("supplier",csrf)).append("<div class='form-grid'>")
                .append(input("Supplier name (use the existing name to update)","name","text","","maxlength='255'"))
                .append(optional("Contact person","contact",255)).append(optional("Email","email",255)).append(optional("Phone / mobile","phone",10)).append(optional("Address","address",1000))
                .append("</div><button>Save supplier</button></form></section><section class='panel'><h3>Contacts</h3><div class='table-wrap'><table><thead><tr><th>Supplier</th><th>Contact</th><th>Email</th><th>Phone</th><th>Address</th></tr></thead><tbody>");
            for(var supplier:service.suppliers(actor)) { out.append("<tr>");for(String key:List.of("name","contact","email","phone","address")) out.append("<td>").append(html(supplier.get(key))).append("</td>");out.append("</tr>"); }
            out.append("</tbody></table></div></section><section class='panel'><h3>Edit quote</h3>").append(form("quote",csrf)).append("<label>Supplier quote<select name='offerId' required>");
            for(var offer:service.offers(actor)) out.append("<option value='").append(offer.get("id")).append("'>").append(html(offer.get("supplier_name")+" · "+offer.get("sku")+" · INR "+offer.get("unit_cost"))).append("</option>");
            out.append("</select></label><div class='form-grid'>").append(input("New unit cost","cost","number","","min='0' step='0.01'"))
                .append(input("Lead time (days)","lead","number","1","min='0' max='365'"))
                .append(input("Minimum quantity","minimum","number","1","min='1'"))
                .append("</div><button>Update quote</button></form></section></section>");
        }
        if(actor.role().allows(Permission.MANAGE_USERS) && service.supportsSqlBackup()) out.append(section("backup","Backup & recovery","Download a complete database backup and restore it into a separate database."))
            .append("<section class='panel'><h3>Database backup</h3><p>Includes inventory, invoices, orders, account password hashes and audit records. Store the file privately.</p><a class='button' href='/export/backup.sql'>Download backup</a><h3>Restore a backup</h3><p>Stop the server, then run the following command with your backup file. The restore creates a separate database and refuses to overwrite an existing one.</p><pre>java -jar target/inventory-management-system-0.1.0-SNAPSHOT.jar \\\n  --restore=/path/to/backup.sql --database=data/restored</pre><p>Start the restored workspace using <code>--serve --database=data/restored --port=8081</code>.</p></section></section>");
        if(actor.role().allows(Permission.MANAGE_USERS) && !service.supportsSqlBackup()) out.append(section("backup","Backup & recovery","PostgreSQL backups are managed outside the application."))
            .append("<section class='panel'><h3>Cloud backups</h3><p>Use your database provider’s backup and restore tools or pg_dump. Test recovery into a separate database before changing the production connection.</p><p>Local H2 SQL downloads cannot restore a PostgreSQL database.</p></section></section>");
        if(actor.role()!=Role.ADMIN) {
        out.append(section("contact-manager",actor.role()==Role.STAFF?"Contact manager":"Reported issues","Report a problem and track its status here."));
        if(actor.role()==Role.STAFF) out.append("<section class='panel'><h3>Report an issue</h3>").append(form("report-issue",csrf))
            .append("<label>Issue type<select required name='subject' id='issue-type'><option value=''>Choose an issue</option><option>Stock quantity mismatch</option><option>Product unavailable</option><option>Damaged product</option><option>Incorrect price</option><option>Invoice or checkout problem</option><option>Technical problem</option><option value='Other'>Other</option></select></label>")
            .append("<label id='other-issue-label'>Other issue<input name='otherSubject' id='other-issue' maxlength='255' placeholder='Describe the issue briefly'></label>")
            .append("<label>Additional details (optional)<textarea name='description' maxlength='2000' rows='5' placeholder='Describe the problem and the product or invoice involved.'></textarea></label><button>Send issue report</button></form></section>");
        out.append("<section class='panel'><h3>Issue reports</h3>");
        var issues=service.issues(actor);
        if(issues.isEmpty()) out.append("<p>No issues reported yet.</p>");
        for(var issue:issues) {
            out.append("<article class='register-row'><h3>").append(html(issue.get("subject"))).append("</h3><p>").append(html(issue.get("description"))).append("</p><small>").append(html(issue.get("reporter_name"))).append(" · ").append(date(issue.get("created_at"))).append(" · ").append(html(issue.get("status"))).append("</small>");
            if(actor.role()!=Role.STAFF && issue.get("status").equals("OPEN")) out.append(form("resolve-issue",csrf)).append(hidden("issueId",issue.get("id"))).append("<button>Mark resolved</button></form>");
            out.append("</article>");
        }
        out.append("</section></section>");
        }
        return restoreForm(out.toString(),submitted,action);
    }
    private static String restoreForm(String page,Map<String,String> submitted,String action) {
        if(!action.startsWith("/business/")) return page;
        var forms=java.util.regex.Pattern.compile("<form[^>]*action='"+java.util.regex.Pattern.quote(action)+"'[^>]*>.*?</form>",java.util.regex.Pattern.DOTALL).matcher(page);
        return forms.replaceAll(formMatch->{
            String form=formMatch.group();
            if(action.equals("/business/checkout")) {
                var row=java.util.regex.Pattern.compile("<div class='sale-line'>.*?</div>",java.util.regex.Pattern.DOTALL).matcher(form);
                if(row.find()) {
                    String original=row.group();StringBuilder restored=new StringBuilder();
                    for(int i=0;i<20;i++) if(submitted.containsKey("sku"+i)) restored.append(original.replace("name='sku0'","name='sku"+i+"'").replace("name='qty0'","name='qty"+i+"'"));
                    form=form.substring(0,row.start())+(restored.isEmpty()?original:restored.toString())+form.substring(row.end());
                }
            }
            var fields=java.util.regex.Pattern.compile("<input[^>]*name='([^']+)'[^>]*>").matcher(form);
            form=fields.replaceAll(field->{
                String name=field.group(1);String tag=field.group();
                if(!name.equals("csrf") && submitted.containsKey(name)) {
                    tag=tag.replaceAll(" value='[^']*'","");
                    tag=tag.substring(0,tag.length()-1)+" value='"+html(submitted.get(name))+"'>";
                }
                return java.util.regex.Matcher.quoteReplacement(tag);
            });
            var selects=java.util.regex.Pattern.compile("<select[^>]*name='([^']+)'[^>]*>.*?</select>",java.util.regex.Pattern.DOTALL).matcher(form);
            form=selects.replaceAll(select->{
                String tag=select.group();String name=select.group(1);
                if(submitted.containsKey(name)) {
                    tag=tag.replace(" selected","");
                    String value="value='"+html(submitted.get(name))+"'";
                    tag=tag.replace(value,value+" selected");
                }
                return java.util.regex.Matcher.quoteReplacement(tag);
            });
            return java.util.regex.Matcher.quoteReplacement(form);
        });
    }
    static String number(Map<String,String> invoice) { return "INV-"+String.format("%06d",Long.parseLong(invoice.get("invoice_number"))); }
    private static String date(String value) {return Instant.parse(value).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();}
    private static String section(String id,String title,String description) {return "<section class='workspace-view business-view' data-view='"+id+"' id='"+id+"'><div class='report-heading'><div><h2>"+title+"</h2><p>"+description+"</p></div></div>";}
    private static String form(String action,String csrf) {return "<form class='business-form' method='post' action='/business/"+action+"'>"+hidden("csrf",csrf)+hidden("requestId",UUID.randomUUID().toString());}
    private static String product(String options) {return "<label>Product<select name='sku' required>"+options+"</select></label>";}
    private static String hidden(String name,String value) {return "<input type='hidden' name='"+name+"' value='"+html(value)+"'>";}
    private static String input(String label,String name,String type,String value,String attrs) {if(type.equals("number")) return NumericFields.render(label,name,value,attrs);return "<label>"+label+"<input required name='"+name+"' type='"+type+"' value='"+html(value)+"' "+attrs+"></label>";}
    private static String optional(String label,String name,int max) {
        String attrs = name.equals("phone")
                ? " type='tel' minlength='10' maxlength='10' pattern='[0-9]{10}' inputmode='numeric' autocomplete='tel-national' data-contact-kind='phone' title='Enter exactly 10 digits'"
                : name.equals("email")
                ? " type='email' maxlength='255' pattern='[^@ ]+@[^@ ]+[.][^@ ]+' autocomplete='email' data-contact-kind='email' title='Enter a valid email address containing @ and a domain'"
                : " type='text' maxlength='"+max+"'";
        return "<label>"+label+"<input id='supplier-"+name+"' name='"+name+"'"+attrs+">"
                +(name.equals("phone") ? "<small class='field-hint'>Exactly 10 digits, without spaces or country code.</small>" : "")+"</label>";
    }

}
