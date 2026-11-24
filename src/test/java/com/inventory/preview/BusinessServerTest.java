package com.inventory.preview;

import com.inventory.model.Product;
import com.inventory.model.details.ChargingDetails;
import com.inventory.repository.jdbc.JdbcInventoryRepository;
import com.inventory.security.*;
import com.inventory.service.InventoryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class BusinessServerTest {
    @TempDir Path temp;
    LocalPreviewServer server;
    InventoryService inventory;
    HttpClient client;
    User manager=new User(UUID.randomUUID(),"manager",Role.MANAGER,true);
    @BeforeEach void setup() throws Exception {
        inventory=new InventoryService(new JdbcInventoryRepository("jdbc:h2:file:"+temp.resolve("inventory")),new AuthorizationService(),Clock.systemUTC());
        inventory.addProduct(manager,new Product("A","USB charger",new BigDecimal("100"),2,true,new ChargingDetails("USB-C",20,"USB PD")));
        inventory.receiveStock(manager,UUID.randomUUID(),"A",10,"Opening");
        client=HttpClient.newHttpClient();server=new LocalPreviewServer(inventory,manager,0);server.start();
    }
    @AfterEach void close(){server.close();client.close();}
    HttpResponse<String> get(String path)throws Exception {return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+server.port()+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> post(String path,Map<String,String> fields)throws Exception {
        String body=fields.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+server.port()+path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String csrf()throws Exception {var m=Pattern.compile("name='csrf' value='([^']+)'").matcher(get("/app").body());assertTrue(m.find());return m.group(1);}
    @Test void supplierFormAndServerRejectInvalidContactValues() throws Exception {
        String page=get("/app").body();
        assertTrue(page.contains("minlength='10' maxlength='10' pattern='[0-9]{10}'"));
        assertTrue(page.contains("type='email'"));
        String token=csrf();
        assertEquals(422,post("/business/supplier",Map.of("csrf",token,"requestId",UUID.randomUUID().toString(),"name","Bad phone","email","contact@example.com","phone","12345678901")).statusCode());
        assertEquals(422,post("/business/supplier",Map.of("csrf",token,"requestId",UUID.randomUUID().toString(),"name","Bad email","email","vbemvbeb","phone","9876543210")).statusCode());
        assertTrue(inventory.business().suppliers(manager).isEmpty());
        assertEquals(303,post("/business/supplier",Map.of("csrf",token,"requestId",UUID.randomUUID().toString(),"name","Valid supplier","email","contact@example.com","phone","9876543210")).statusCode());
        assertEquals("9876543210",inventory.business().suppliers(manager).getFirst().get("phone"));
    }

    @Test void staffOnlySeesElectronicsInCatalogueAndSaleChoices() throws Exception {
        inventory.addProduct(manager,new Product("PEN","Blue pen","Stationery",new BigDecimal("10"),1,true));
        server.close();
        server=new LocalPreviewServer(inventory,new User(UUID.randomUUID(),"staff",Role.STAFF,true),0);
        server.start();
        for(String path:List.of("/app","/app?scope=all","/app?scope=legacy&sku=PEN")) {
            String page=get(path).body();
            assertTrue(page.contains("USB charger"));
            assertFalse(page.contains("data-nav='dashboard'"));
            assertFalse(page.contains("data-view='dashboard'"));
            assertTrue(page.contains("data-initial-view=\""+(path.equals("/app")?"new-sale":"management")+"\""));
            assertFalse(page.contains("Blue pen"));
            assertFalse(page.contains("Legacy records"));
            assertFalse(page.contains("value='PEN'"));
        }
    }
    @Test void invoiceFormsSaveAndErrorsPreserveEnteredLines()throws Exception {
        String initial=get("/app").body();
        assertFalse(initial.contains("data-nav='serials'"));
        assertFalse(initial.contains("name='serials0'"));
        assertTrue(initial.contains("data-value-type='integer'"));
        String token=csrf();UUID id=UUID.randomUUID();
        var fields=new HashMap<>(Map.of("csrf",token,"requestId",id.toString(),"customer","Buyer <One>","discount","0","sku0","A","qty0","11","serials0",""));
        var rejected=post("/business/checkout",fields);assertEquals(409,rejected.statusCode());
        assertTrue(rejected.body().contains("Buyer &lt;One&gt;"));assertTrue(rejected.body().contains("value='11'"));
        assertTrue(rejected.body().contains("data-initial-view=\"new-sale\""));
        fields.put("qty0","2");assertEquals(303,post("/business/checkout",fields).statusCode());
        var invoice=get("/app?invoice="+id);assertEquals(200,invoice.statusCode());assertTrue(invoice.body().contains("INV-000001"));assertTrue(invoice.body().contains("200.00"));
        assertEquals(8,inventory.getStock(manager,"A").quantity());
    }
    @Test void staffCheckoutOpensSavedInvoiceAndKeepsOrderHistory() throws Exception {
        inventory.addProduct(manager,new Product("B","Spare charger",new BigDecimal("50"),1,true,new ChargingDetails("USB-C",10,"USB PD")));
        inventory.receiveStock(manager,UUID.randomUUID(),"B",5,"Opening");
        server.close();server=new LocalPreviewServer(inventory,new User(UUID.randomUUID(),"staff",Role.STAFF,true),0);server.start();
        String page=get("/app").body();
        assertTrue(page.contains("data-initial-view=\"new-sale\""));
        assertTrue(page.contains("data-nav='order-history'"));
        assertTrue(page.contains("id='cart-search'"));
        String id=UUID.randomUUID().toString();
        var fields=Map.of("csrf",csrf(),"requestId",id,"customer","Counter buyer","discount","10.00","sku0","A","qty0","2","sku1","B","qty1","3");
        var saved=post("/business/checkout",fields);
        assertEquals(303,saved.statusCode());
        assertEquals("/app?invoice="+id+"&notice=invoice#invoice-document",saved.headers().firstValue("Location").orElseThrow());
        assertEquals(303,post("/business/checkout",fields).statusCode());
        assertEquals(8,inventory.getStock(manager,"A").quantity());
        assertEquals(2,inventory.getStock(manager,"B").quantity());
        assertEquals(1,inventory.business().invoices(manager).size());
        String invoice=get("/app?invoice="+id).body();
        assertTrue(invoice.contains("Counter buyer"));
        assertTrue(invoice.contains("INR 340.00"));
        assertTrue(invoice.contains("id='invoice-document'"));
        assertTrue(invoice.contains("id='print-invoice'"));
    }
    @Test void javaRejectsInvalidIntegerAndMoneyInputWithoutChangingStock() throws Exception {
        String token=csrf();
        for(String invalid:List.of("1.5","3e2","abc","-1","2147483648")) {
            var response=post("/business/count",Map.of("csrf",token,"requestId",UUID.randomUUID().toString(),"sku","A","counted",invalid,"reason","Test count"));
            assertEquals(422,response.statusCode());
            assertTrue(response.body().contains("whole number") || response.body().contains("2,147,483,647"));
        }
        for(String invalid:List.of("1.234","1e2","free")) {
            assertEquals(422,post("/business/checkout",Map.of("csrf",token,"requestId",UUID.randomUUID().toString(),"customer","Buyer","discount",invalid,"sku0","A","qty0","1")).statusCode());
        }
        assertEquals(10,inventory.getStock(manager,"A").quantity());
        assertTrue(inventory.business().counts(manager).isEmpty());
        assertTrue(inventory.business().invoices(manager).isEmpty());
        assertEquals(405,post("/business/serial",Map.of("csrf",token)).statusCode());
        assertEquals(404,get("/business/serial").statusCode());
    }
    @Test void staffCannotAccessBackupOrWriteManagementWorkflows()throws Exception {
        server.close();server=new LocalPreviewServer(inventory,new User(UUID.randomUUID(),"staff",Role.STAFF,true),0);server.start();
        String page=get("/app").body();assertTrue(page.contains("data-nav='new-sale'"));assertFalse(page.contains("data-nav='stocktaking'"));assertFalse(page.contains("data-nav='backup'"));
        assertEquals(403,get("/export/backup.sql").statusCode());
        assertEquals(403,post("/business/count",Map.of("csrf",csrf(),"requestId",UUID.randomUUID().toString(),"sku","A","counted","1","reason","Count")).statusCode());
        assertEquals(403,post("/business/checkout",Map.of("csrf","invalid")).statusCode());
    }
    @Test void quickStockSaleIsRestrictedToManagersAndAdmins() throws Exception {
        for(Role role:List.of(Role.STAFF,Role.MANAGER,Role.ADMIN)) {
            server.close();server=new LocalPreviewServer(inventory,new User(UUID.randomUUID(),"test",role,true),0);server.start();
            String page=get("/app").body();
            assertEquals(role!=Role.STAFF,page.contains("Quick stock sale"));
            int before=inventory.getStock(manager,"A").quantity();
            var response=post("/stock/sell",Map.of("csrf",csrf(),"requestId",UUID.randomUUID().toString(),"sku","A","quantity","1","reason","Counter sale"));
            assertEquals(role==Role.STAFF?403:303,response.statusCode());
            assertEquals(before-(role==Role.STAFF?0:1),inventory.getStock(manager,"A").quantity());
        }
    }
    @Test void issueReportsArePrivateToReporterAndManagersCanResolve() {
        User staff=new User(UUID.randomUUID(),"reporter",Role.STAFF,true);
        User other=new User(UUID.randomUUID(),"other",Role.STAFF,true);
        var service=inventory.business();UUID id=UUID.randomUUID();
        service.reportIssue(staff,id,"Wrong stock","Charger stock needs checking");
        service.reportIssue(staff,id,"Wrong stock","Charger stock needs checking");
        assertEquals(1,service.issues(staff).size());
        assertTrue(service.issues(other).isEmpty());
        assertEquals(1,service.issues(manager).size());
        assertThrows(com.inventory.exception.AccessDeniedException.class,()->service.resolveIssue(staff,id));
        service.resolveIssue(manager,id);
        assertEquals("RESOLVED",service.issues(staff).getFirst().get("status"));
    }
    @Test void removedBarcodeRoutesAndBackupDownloadWork()throws Exception {
        assertEquals(404,get("/lookup?code=987654").statusCode());
        assertEquals(405,post("/business/barcode",Map.of("csrf",csrf())).statusCode());
        assertFalse(get("/app").body().contains("barcode-settings"));
        server.close();server=new LocalPreviewServer(inventory,new User(UUID.randomUUID(),"admin",Role.ADMIN,true),0);server.start();
        var backup=get("/export/backup.sql");assertEquals(200,backup.statusCode());assertTrue(backup.body().contains("PRODUCTS"));assertTrue(backup.headers().firstValue("Content-Disposition").orElseThrow().contains("attachment"));
    }
}
