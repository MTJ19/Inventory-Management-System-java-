package com.inventory.service;

import com.inventory.exception.*;
import com.inventory.model.*;
import com.inventory.model.details.ChargingDetails;
import com.inventory.model.procurement.*;
import com.inventory.repository.jdbc.JdbcInventoryRepository;
import com.inventory.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessServiceTest {
    @TempDir Path temp;
    InventoryService inventory;
    BusinessService business;
    String url;
    final User manager=new User(UUID.randomUUID(),"manager",Role.MANAGER,true);
    final User reviewer=new User(UUID.randomUUID(),"reviewer",Role.MANAGER,true);
    final User staff=new User(UUID.randomUUID(),"staff",Role.STAFF,true);
    final User admin=new User(UUID.randomUUID(),"admin",Role.ADMIN,true);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-18T09:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void setup() {
        url="jdbc:h2:file:"+temp.resolve("inventory");
        inventory=new InventoryService(new JdbcInventoryRepository(url),new AuthorizationService(),clock);
        business=inventory.business();
        for(String sku:List.of("A","B")) {
            inventory.addProduct(manager,new Product(sku,"Product "+sku,new BigDecimal("100.00"),2,true,new ChargingDetails("USB-C",20,"USB PD")));
            inventory.receiveStock(manager,UUID.randomUUID(),sku,10,"Opening stock");
        }
    }
    BusinessService.SaleLine line(String sku,int quantity) {return new BusinessService.SaleLine(sku,quantity);}
    UUID sale(int quantity) {UUID id=UUID.randomUUID();business.checkout(staff,id,"Customer",BigDecimal.TEN,List.of(line("A",quantity)));return id;}
    @Test void invoiceSnapshotsPricesAndRetriesWithoutSellingTwice() {
        UUID id=sale(2);
        inventory.updateProduct(manager,"A","Renamed",new BigDecimal("200"),2,new ChargingDetails("USB-C",20,"USB PD"));
        assertEquals("100.00",business.lines(staff,id).getFirst().get("unit_price"));
        business.checkout(staff,id,"Customer",BigDecimal.TEN,List.of(line("A",2)));
        assertEquals(8,inventory.getStock(manager,"A").quantity());
        assertEquals("190.00",business.invoices(staff).getFirst().get("total"));
        assertThrows(InventoryException.class,()->business.checkout(staff,id,"Customer",BigDecimal.TEN,List.of(line("A",3))));
        assertThrows(InventoryException.class,()->business.checkout(manager,id,"Customer",BigDecimal.TEN,List.of(line("A",2))));
    }
    @Test void invalidSecondLineAndDatabaseFailuresRollbackEntireInvoice() throws Exception {
        assertThrows(InventoryException.class,()->business.checkout(staff,UUID.randomUUID(),"Buyer",BigDecimal.ZERO,List.of(line("A",2),line("B",11))));
        assertEquals(10,inventory.getStock(manager,"A").quantity());assertTrue(business.invoices(staff).isEmpty());
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){s.execute("ALTER TABLE stock_movements ADD CONSTRAINT fail_sale CHECK(quantity>0)");}
        assertThrows(PersistenceException.class,()->sale(1));
        assertTrue(business.invoices(staff).isEmpty());assertEquals(10,inventory.getStock(manager,"A").quantity());
    }
    @Test void discountsAndDuplicateLinesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->business.checkout(staff,UUID.randomUUID(),"Buyer",new BigDecimal("101"),List.of(line("A",1))));
        assertThrows(IllegalArgumentException.class,()->business.checkout(staff,UUID.randomUUID(),"Buyer",BigDecimal.ZERO,List.of(line("A",1),line("A",1))));
        assertTrue(business.invoices(staff).isEmpty());
    }
    @Test void returnsAreLinkedLimitedAndDamagedUnitsDoNotReenterStock() {
        UUID invoice=sale(3), request=UUID.randomUUID();
        business.returnSale(manager,request,invoice,1,1,"RESELLABLE","Unopened");
        business.returnSale(manager,request,invoice,1,1,"RESELLABLE","Unopened");
        assertEquals(8,inventory.getStock(manager,"A").quantity());
        business.returnSale(manager,UUID.randomUUID(),invoice,1,2,"DAMAGED","Damaged");
        assertEquals(8,inventory.getStock(manager,"A").quantity());
        assertThrows(InventoryException.class,()->business.returnSale(manager,UUID.randomUUID(),invoice,1,1,"RESELLABLE","Extra"));
        assertEquals(3,inventory.operations().analytics(manager,LocalDate.of(2026,11,18),LocalDate.of(2026,11,18)).soldUnits());
    }
    @Test void stockCountsRequireIndependentReviewAndRejectStaleBalances() {
        UUID count=UUID.randomUUID();business.count(manager,count,"A",7,"Physical count");
        assertThrows(InventoryException.class,()->business.reviewCount(manager,count,true));
        business.reviewCount(reviewer,count,true);assertEquals(7,inventory.getStock(manager,"A").quantity());
        assertEquals(MovementType.ADJUSTMENT,inventory.history(manager,"A").getLast().type());
        assertThrows(InventoryException.class,()->business.reviewCount(reviewer,count,true));
        UUID stale=UUID.randomUUID();business.count(manager,stale,"A",8,"Recount");sale(1);
        assertThrows(InventoryException.class,()->business.reviewCount(reviewer,stale,true));
        assertEquals(6,inventory.getStock(manager,"A").quantity());
    }
    @Test void retiredSerialRecordsDoNotBlockSalesReturnsOrCounts() throws Exception {
        try(var c=DriverManager.getConnection(url,"sa","");var statement=c.createStatement()) {
            statement.execute("INSERT INTO serial_units(serial,sku,warranty_months,status) SELECT 'OLD-'||X,'A',12,'AVAILABLE' FROM SYSTEM_RANGE(1,10)");
        }
        UUID invoice=sale(2);
        business.returnSale(manager,UUID.randomUUID(),invoice,1,1,"RESELLABLE","Unopened");
        assertEquals(9,inventory.getStock(manager,"A").quantity());
        UUID count=UUID.randomUUID();business.count(manager,count,"A",5,"Physical count");business.reviewCount(reviewer,count,true);
        assertEquals(5,inventory.getStock(manager,"A").quantity());
    }
    @Test void concurrentInvoicesCannotOversell() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=List.<Callable<Boolean>>of(()->trySale(),()->trySale());int successes=0;
            for(var result:pool.invokeAll(tasks)) if(result.get()) successes++;
            assertEquals(1,successes);assertEquals(3,inventory.getStock(manager,"A").quantity());
        }
    }
    boolean trySale(){try{sale(7);return true;}catch(InventoryException e){return false;}}
    @Test void supplierContactsRequireTenDigitPhonesAndValidEmails() {
        for(String invalid:List.of("123456789", "12345678901", "98765abc10", "+919876543210", "98765 43210", "98765-43210")) {
            assertThrows(IllegalArgumentException.class,()->business.supplier(manager,"Invalid phone","Contact","contact@example.com",invalid,""));
        }
        for(String invalid:List.of("vbemvbeb", "missing-at.example.com", "@example.com", "name@", "a@@example.com", "name@example")) {
            assertThrows(IllegalArgumentException.class,()->business.supplier(manager,"Invalid email","Contact",invalid,"9876543210",""));
        }
        assertTrue(business.suppliers(manager).isEmpty());
        business.supplier(manager,"Valid supplier","Contact","contact@example.com","0123456789","");
        assertEquals("0123456789",business.suppliers(manager).getFirst().get("phone"));
        business.supplier(manager,"Optional contacts","Contact","","","");
        assertEquals(2,business.suppliers(manager).size());
    }

    @Test void supplierQuoteEditsPreserveOrderPriceAndPurchaseBatchRollsBack() {
        var ops=inventory.operations(); UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        ops.addOffer(manager,new SupplierOffer(a,"A","Supplier",new BigDecimal("50"),2,1));
        ops.addOffer(manager,new SupplierOffer(b,"B","Supplier",new BigDecimal("60"),2,1));
        UUID group=UUID.randomUUID();ops.createBatch(manager,group,List.of(a,b),List.of(2,3),"Reorder");
        business.editOffer(manager,a,new BigDecimal("70"),4,2);
        assertEquals(new BigDecimal("50.00"),ops.snapshot(manager).orders().stream().filter(o->o.sku().equals("A")).findFirst().orElseThrow().unitCost());
        ops.transitionBatch(manager,group,OrderAction.SUBMIT,"Submit");
        assertThrows(InventoryException.class,()->ops.transitionBatch(manager,group,OrderAction.APPROVE,"Self"));
        ops.transitionBatch(reviewer,group,OrderAction.APPROVE,"Reviewed");
        assertTrue(ops.snapshot(manager).orders().stream().allMatch(o->o.status()==OrderStatus.APPROVED));
        assertThrows(InventoryException.class,()->ops.createBatch(manager,UUID.randomUUID(),List.of(b,a),List.of(3,2),"Duplicate"));
        assertEquals(2,ops.snapshot(manager).orders().size());
    }
    @Test void backupRestoresNewDatabaseAndRestrictsStaff() throws Exception {
        UUID invoice=sale(2);
        assertThrows(AccessDeniedException.class,()->business.backup(staff));
        assertThrows(AccessDeniedException.class,()->business.count(staff,UUID.randomUUID(),"A",1,"Count"));
        String backup=business.backup(admin);String restored="jdbc:h2:file:"+temp.resolve("restored");
        try(var c=DriverManager.getConnection(restored,"sa","")){org.h2.tools.RunScript.execute(c,new java.io.StringReader(backup));}
        var reopened=new InventoryService(new JdbcInventoryRepository(restored),new AuthorizationService(),clock);
        assertEquals(8,reopened.getStock(manager,"A").quantity());assertEquals(2,Integer.parseInt(reopened.business().lines(staff,invoice).getFirst().get("quantity")));
    }
}
