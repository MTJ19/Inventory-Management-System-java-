package com.inventory.service;

import com.inventory.exception.InventoryException;
import com.inventory.exception.PersistenceException;
import com.inventory.security.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Persistent retail workflows. All stock and document writes share the inventory transaction lock. */
public final class BusinessService {
    public record SaleLine(String sku, int quantity) {
        public SaleLine { sku = com.inventory.model.Product.normalizeSku(sku); }
    }
    private final com.inventory.database.Database database;
    private final AuthorizationService authorization;
    private final Clock clock;
    public BusinessService(String url, AuthorizationService authorization, Clock clock) {
        this(com.inventory.database.Database.h2(url),authorization,clock);
    }
    public BusinessService(com.inventory.database.Database database,AuthorizationService authorization,Clock clock) {
        this.database=database; this.authorization=authorization; this.clock=clock;
    }
    private void require(User actor, Permission permission) { authorization.require(actor,permission); }
    private static String text(String value,int max,String label) {
        if(value==null || value.isBlank() || value.strip().length()>max) throw new IllegalArgumentException(label+" is required (maximum "+max+" characters)");
        return value.strip();
    }
    private static BigDecimal money(BigDecimal value) {
        if(value==null || value.signum()<0 || value.precision()>18) throw new IllegalArgumentException("Enter a valid nonnegative amount");
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
    public UUID checkout(User actor, UUID id, String customer, BigDecimal discount, List<SaleLine> lines) {
        require(actor,Permission.SELL_STOCK);
        String buyer=text(customer,255,"Customer"); BigDecimal reduction=money(discount);
        if(lines.isEmpty() || lines.size()>20) throw new IllegalArgumentException("Add between 1 and 20 invoice lines");
        String fingerprint=actor.id()+"|"+buyer+"|"+reduction+"|"+lines;
        return tx(c->{
            var existing=rows(c,"SELECT request_data FROM invoices WHERE id=?",id);
            if(!existing.isEmpty()) {
                if(!existing.getFirst().get("request_data").equals(fingerprint)) throw new InventoryException("Invoice request already used with different data");
                return id;
            }
            Set<String> skus=new HashSet<>();
            BigDecimal subtotal=BigDecimal.ZERO; List<Map<String,String>> products=new ArrayList<>();
            for(var line:lines) {
                if(line.quantity()<1 || !skus.add(line.sku())) throw new IllegalArgumentException("Use one positive quantity per product");
                var product=one(c,"SELECT * FROM products WHERE sku=? AND active=TRUE",line.sku());
                if(Integer.parseInt(product.get("quantity"))<line.quantity()) throw new InventoryException("Insufficient stock for "+line.sku());
                products.add(product); subtotal=subtotal.add(new BigDecimal(product.get("selling_price")).multiply(BigDecimal.valueOf(line.quantity())));
            }
            if(reduction.compareTo(subtotal)>0) throw new IllegalArgumentException("Discount cannot exceed the subtotal");
            execute(c,"INSERT INTO invoices(id,customer,discount,subtotal,total,actor_id,created_at,request_data) VALUES(?,?,?,?,?,?,?,?)",
                id,buyer,reduction,subtotal,subtotal.subtract(reduction),actor.id(),clock.instant(),fingerprint);
            for(int i=0;i<lines.size();i++) {
                var line=lines.get(i); var product=products.get(i);
                execute(c,"INSERT INTO invoice_lines(invoice_id,line_no,sku,product_name,quantity,unit_price) VALUES(?,?,?,?,?,?)",id,i+1,line.sku(),product.get("name"),line.quantity(),new BigDecimal(product.get("selling_price")));
                movement(c,UUID.nameUUIDFromBytes((id+":"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)),line.sku(),-line.quantity(),actor,"Invoice "+id);

            }
            return id;
        });
    }
    public void returnSale(User actor,UUID id,UUID invoice,int line,int quantity,String disposition,String reason) {
        require(actor,Permission.RECEIVE_STOCK); String note=text(reason,2000,"Return reason");
        if(quantity<1 || !Set.of("RESELLABLE","DAMAGED").contains(disposition)) throw new IllegalArgumentException("Choose a positive return quantity and condition");
        tx(c->{
            var old=rows(c,"SELECT * FROM sales_returns WHERE id=?",id);
            if(!old.isEmpty()) {
                var r=old.getFirst();
                if(!r.get("invoice_id").equals(invoice.toString()) || !r.get("line_no").equals(""+line) || !r.get("quantity").equals(""+quantity) || !r.get("disposition").equals(disposition) || !r.get("reason").equals(note) || !r.get("actor_id").equals(actor.id().toString())) throw new InventoryException("Return request already used");
                return null;
            }
            var item=one(c,"SELECT * FROM invoice_lines WHERE invoice_id=? AND line_no=?",invoice,line);
            if(quantity>Integer.parseInt(item.get("quantity"))-Integer.parseInt(item.get("returned"))) throw new InventoryException("Return exceeds the remaining sold quantity");
            String sku=item.get("sku");
            execute(c,"INSERT INTO sales_returns(id,invoice_id,line_no,quantity,disposition,reason,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?)",id,invoice,line,quantity,disposition,note,actor.id(),clock.instant());
            execute(c,"UPDATE invoice_lines SET returned=returned+? WHERE invoice_id=? AND line_no=?",quantity,invoice,line);
            if(disposition.equals("RESELLABLE")) movement(c,id,sku,quantity,actor,"Return against invoice "+invoice+": "+note);
            return null;
        });
    }
    public void count(User actor,UUID id,String sku,int counted,String reason) {
        require(actor,Permission.MANAGE_PRODUCTS); String note=text(reason,2000,"Count reason");
        if(counted<0) throw new IllegalArgumentException("Count cannot be negative");
        tx(c->{
            var old=rows(c,"SELECT * FROM stock_counts WHERE id=?",id);
            if(!old.isEmpty()) { var r=old.getFirst(); if(!r.get("sku").equals(sku) || !r.get("counted").equals(""+counted) || !r.get("reason").equals(note) || !r.get("created_by").equals(actor.id().toString())) throw new InventoryException("Count request already used"); return null; }
            var p=one(c,"SELECT quantity FROM products WHERE sku=? AND active=TRUE",sku);
            execute(c,"INSERT INTO stock_counts(id,sku,expected,counted,reason,created_by,created_at) VALUES(?,?,?,?,?,?,?)",id,sku,Integer.parseInt(p.get("quantity")),counted,note,actor.id(),clock.instant()); return null;
        });
    }
    public void reviewCount(User actor,UUID id,boolean approve) {
        require(actor,Permission.APPROVE_PURCHASES);
        tx(c->{var count=one(c,"SELECT * FROM stock_counts WHERE id=?",id);
            if(!count.get("status").equals("PENDING")) throw new InventoryException("This count has already been reviewed");
            if(count.get("created_by").equals(actor.id().toString())) throw new InventoryException("Another Manager or Admin must review this count");
            if(approve) {
                int current=Integer.parseInt(one(c,"SELECT quantity FROM products WHERE sku=?",count.get("sku")).get("quantity"));
                if(current!=Integer.parseInt(count.get("expected"))) throw new InventoryException("Stock changed after counting. Reject this count and submit a fresh count");
                int counted=Integer.parseInt(count.get("counted"));
                int delta=counted-current;
                if(delta!=0) movement(c,id,count.get("sku"),delta,actor,"Stock count adjustment: "+count.get("reason"));
            }
            execute(c,"UPDATE stock_counts SET status=?,reviewed_by=? WHERE id=?",approve?"APPROVED":"REJECTED",actor.id(),id);return null;
        });
    }
    public void supplier(User actor,String name,String contact,String email,String phone,String address) {
        require(actor,Permission.MANAGE_PURCHASES); String supplier=text(name,255,"Supplier name");
        if(contact.length()>255 || email.length()>255 || address.length()>1000) throw new IllegalArgumentException("Supplier contact details are too long");
        if(!email.isBlank() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter a valid email address.");
        if(!phone.isBlank() && !phone.matches("[0-9]{10}")) throw new IllegalArgumentException("Phone/mobile number must contain exactly 10 digits.");
        tx(c->{execute(c,database.postgres()?"INSERT INTO supplier_contacts(name,contact,email,phone,address) VALUES(?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET contact=EXCLUDED.contact,email=EXCLUDED.email,phone=EXCLUDED.phone,address=EXCLUDED.address":"MERGE INTO supplier_contacts KEY(name) VALUES(?,?,?,?,?)",supplier,contact.strip(),email.strip(),phone.strip(),address.strip());return null;});
    }
    public void editOffer(User actor,UUID id,BigDecimal cost,int lead,int minimum) {
        require(actor,Permission.MANAGE_PURCHASES); BigDecimal price=money(cost);
        if(lead<0 || lead>365 || minimum<1) throw new IllegalArgumentException("Enter valid lead time and minimum quantity");
        tx(c->{one(c,"SELECT id FROM supplier_offers WHERE id=?",id);execute(c,"UPDATE supplier_offers SET unit_cost=?,lead_days=?,minimum_quantity=? WHERE id=?",price,lead,minimum,id);return null;});
    }
    public void reportIssue(User actor,UUID id,String subject,String description) {
        require(actor,Permission.VIEW_INVENTORY);
        if(actor.role()!=Role.STAFF) throw new com.inventory.exception.AccessDeniedException("Only Staff can report issues.");
        String title=text(subject,255,"Subject"),detail=text(description==null || description.isBlank()?subject:description,2000,"Issue details");
        tx(c->{
            var existing=rows(c,"SELECT * FROM issue_reports WHERE id=?",id);
            if(!existing.isEmpty()) {
                var prior=existing.getFirst();
                if(!prior.get("reporter_id").equals(actor.id().toString()) || !prior.get("subject").equals(title) || !prior.get("description").equals(detail)) throw new InventoryException("Issue request already used");
                return null;
            }
            execute(c,"INSERT INTO issue_reports(id,reporter_id,reporter_name,subject,description,status,created_at) VALUES(?,?,?,?,?,?,?)",id,actor.id(),actor.username(),title,detail,"OPEN",clock.instant());return null;
        });
    }
    public List<Map<String,String>> issues(User actor) {
        require(actor,Permission.VIEW_INVENTORY);
        if(actor.role()==Role.ADMIN) throw new com.inventory.exception.AccessDeniedException("Issue reports are for Staff and Managers.");
        return tx(c->actor.role()==Role.STAFF?rows(c,"SELECT * FROM issue_reports WHERE reporter_id=? ORDER BY created_at DESC",actor.id()):rows(c,"SELECT * FROM issue_reports ORDER BY created_at DESC"));
    }
    public void resolveIssue(User actor,UUID id) {
        require(actor,Permission.MANAGE_PRODUCTS);
        if(actor.role()!=Role.MANAGER) throw new com.inventory.exception.AccessDeniedException("Only Managers can resolve issues.");
        tx(c->{execute(c,"UPDATE issue_reports SET status='RESOLVED' WHERE id=?",id);return null;});
    }
    public List<Map<String,String>> invoices(User actor) {require(actor,Permission.SELL_STOCK);return tx(c->rows(c,"SELECT * FROM invoices ORDER BY invoice_number DESC"));}
    public List<Map<String,String>> lines(User actor,UUID invoice) {require(actor,Permission.SELL_STOCK);return tx(c->rows(c,"SELECT * FROM invoice_lines WHERE invoice_id=? ORDER BY line_no",invoice));}
    public List<Map<String,String>> returns(User actor) {require(actor,Permission.RECEIVE_STOCK);return tx(c->rows(c,"SELECT * FROM sales_returns ORDER BY created_at DESC"));}
    public List<Map<String,String>> counts(User actor) {require(actor,Permission.MANAGE_PRODUCTS);return tx(c->rows(c,"SELECT * FROM stock_counts ORDER BY created_at DESC"));}
    public List<Map<String,String>> suppliers(User actor) {require(actor,Permission.MANAGE_PURCHASES);return tx(c->rows(c,"SELECT * FROM supplier_contacts ORDER BY name"));}
    public List<Map<String,String>> offers(User actor) {require(actor,Permission.MANAGE_PURCHASES);return tx(c->rows(c,"SELECT * FROM supplier_offers ORDER BY supplier_name,sku"));}
    public List<Map<String,String>> purchaseLines(User actor) {require(actor,Permission.MANAGE_PURCHASES);return tx(c->rows(c,"SELECT * FROM purchase_orders WHERE group_id IS NOT NULL ORDER BY created_at DESC,id"));}
    public boolean supportsSqlBackup() {return !database.postgres();}
    public String backup(User actor) {
        require(actor,Permission.MANAGE_USERS);
        if(database.postgres()) throw new IllegalArgumentException("Use provider backups or pg_dump for PostgreSQL. SQL download is available only for local H2 storage.");
        return tx(c->{StringBuilder script=new StringBuilder();try(var s=c.createStatement();var rs=s.executeQuery("SCRIPT")){while(rs.next()) script.append(rs.getString(1)).append('\n');}return script.toString();});
    }
    private void movement(Connection c,UUID id,String sku,int delta,User actor,String reason) throws SQLException {
        var p=one(c,"SELECT * FROM products WHERE sku=?",sku); long balance=(long)Integer.parseInt(p.get("quantity"))+delta;
        if(balance<0 || balance>Integer.MAX_VALUE) throw new InventoryException("Stock quantity is outside the supported range");
        execute(c,"UPDATE products SET quantity=? WHERE sku=?",balance,sku);
        // Returns and adjustments remain distinct from trading activity in analytics.
        execute(c,"INSERT INTO stock_movements(request_id,sku,movement_type,quantity,balance_after,actor_id,reason,occurred_at) VALUES(?,?,?,?,?,?,?,?)",id,sku,reason.startsWith("Stock count adjustment:")?"ADJUSTMENT":reason.startsWith("Return against invoice ")?"RETURN":"SALE",delta,balance,actor.id(),reason,clock.instant());
    }
    private static void execute(Connection c,String sql,Object... args) throws SQLException {try(var s=c.prepareStatement(sql)){bind(s,args);s.executeUpdate();}}
    private static void bind(PreparedStatement s,Object... args) throws SQLException {for(int i=0;i<args.length;i++) s.setObject(i+1,args[i] instanceof UUID || args[i] instanceof Instant ? args[i].toString():args[i]);}
    private static Map<String,String> one(Connection c,String sql,Object... args) throws SQLException {var result=rows(c,sql,args);if(result.isEmpty()) throw new InventoryException("Record not found or no longer available");return result.getFirst();}
    private static List<Map<String,String>> rows(Connection c,String sql,Object... args) throws SQLException {
        List<Map<String,String>> result=new ArrayList<>();try(var s=c.prepareStatement(sql)){bind(s,args);try(var rs=s.executeQuery()){while(rs.next()){Map<String,String> row=new LinkedHashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),Objects.toString(rs.getString(i),""));result.add(Collections.unmodifiableMap(row));}}}return List.copyOf(result);
    }
    private <T>T tx(Sql<T> work) {
        try(var c=database.connect()){c.setAutoCommit(false);try {
            try(var s=c.prepareStatement("SELECT id FROM inventory_write_lock WHERE id=1 FOR UPDATE");var rs=s.executeQuery()){if(!rs.next()) throw new SQLException("Inventory lock missing");}
            T result=work.run(c);c.commit();return result;
        }catch(SQLException|RuntimeException e){c.rollback();throw e;}}
        catch(SQLException e){throw new PersistenceException("Business operation failed",e);}
    }
    @FunctionalInterface private interface Sql<T>{T run(Connection c)throws SQLException;}
}
