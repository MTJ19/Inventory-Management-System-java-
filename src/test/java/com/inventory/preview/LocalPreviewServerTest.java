package com.inventory.preview;

import com.inventory.model.Product;
import com.inventory.repository.memory.InMemoryInventoryRepository;
import com.inventory.security.AuthorizationService;
import com.inventory.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LocalPreviewServerTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private LocalPreviewServer server;
    private InventoryService inventory;

    @BeforeEach
    void start() throws Exception {
        inventory = new InventoryService(new InMemoryInventoryRepository(), new AuthorizationService(), Clock.systemUTC());
        DemoData.seed(inventory);
        server = new LocalPreviewServer(inventory, DemoData.MANAGER, 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
        client.close();
    }

    @Test
    void landingAndApplicationRemainSeparateAndVideoSupportsSeeking() throws Exception {
        var landing = get("/");
        assertEquals(200, landing.statusCode());
        assertTrue(landing.body().contains("Login"));
        assertTrue(landing.body().contains("name='role' value='ADMIN'"));
        assertTrue(landing.body().contains("How it works"));
        assertFalse(landing.body().contains(" loop"));
        assertTrue(landing.headers().firstValue("Content-Security-Policy").orElseThrow().contains("media-src 'self'"));
        assertTrue(get("/app").body().contains("action='/app'"));
        assertEquals(200, get("/assets/landing.js").statusCode());
        assertEquals(200, get("/assets/landing.css").statusCode());
        assertEquals(200, get("/assets/landing-final.jpg").statusCode());
        var bytes = client.send(HttpRequest.newBuilder(uri("/assets/landing-intro.mp4"))
                .header("Range", "bytes=0-31").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, bytes.statusCode());
        assertEquals(32, bytes.body().length);
        assertEquals("video/mp4", bytes.headers().firstValue("Content-Type").orElseThrow());
        for (String range : java.util.List.of("bytes=999999999-", "bytes=-0", "bytes=30-2", "bytes=0-1,3-4")) {
            var invalid = client.send(HttpRequest.newBuilder(uri("/assets/landing-intro.mp4"))
                    .header("Range", range).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(416, invalid.statusCode());
        }
        var tail = client.send(HttpRequest.newBuilder(uri("/assets/landing-intro.mp4"))
                .header("Range", "bytes=-64").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, tail.statusCode());
        assertEquals(64, tail.body().length);
    }

    @Test
    void previewShowsActualStockAndSelectedProductHistory() throws Exception {
        var response = get("/?sku=VE-CHG-001");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("65W USB-C fast charger"));
        assertTrue(response.body().contains("Demo: counter sale"));
        assertTrue(response.body().contains("Balance 4"));
        assertTrue(response.body().contains("Quick actions"));
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("text/html"));
    }

    @Test
    void jsonReportsStockAndDoesNotMutateIt() throws Exception {
        var response = get("/api/inventory");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"sku\":\"VE-CHG-001\""));
        assertTrue(response.body().contains("\"quantity\":4"));
        assertEquals(4, inventory.getStock(DemoData.MANAGER, "VE-CHG-001").quantity());
    }

    @Test
    void rejectsWritesAndUnknownRoutes() throws Exception {
        var request = HttpRequest.newBuilder(uri("/"))
                .POST(HttpRequest.BodyPublishers.ofString("change-stock")).build();
        assertEquals(405, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, get("/missing").statusCode());
        assertEquals(404, get("/?sku=MISSING").statusCode());
        assertEquals(400, get("/?sku=bad%20sku").statusCode());
        assertEquals("ok", get("/health").body());
    }

    @Test
    void escapesProductNamesInHtmlAndJson() throws Exception {
        inventory.addProduct(DemoData.MANAGER, new Product("XSS", "<script>\"test\"</script>",
                "Test", BigDecimal.ONE, 0, true));
        String page = get("/?sku=XSS").body();
        assertFalse(page.contains("<script>"));
        assertTrue(page.contains("&lt;script&gt;&quot;test&quot;&lt;/script&gt;"));
        assertTrue(get("/api/inventory").body().contains("<script>\\\"test\\\"</script>"));
    }

    @Test
    void repeatedSeedingDoesNotDuplicateDemoMovements() {
        DemoData.seed(inventory);
        assertEquals(6, inventory.listProducts(DemoData.MANAGER).size());
        assertEquals(4, inventory.getStock(DemoData.MANAGER, "VE-CHG-001").quantity());
        assertEquals(2, inventory.history(DemoData.MANAGER, "VE-CHG-001").size());
    }

    @Test
    void productReceiptSaleAndEditWorkThroughHttp() throws Exception {
        server.close();server=new LocalPreviewServer(inventory,new com.inventory.security.User(UUID.randomUUID(),"admin",com.inventory.security.Role.ADMIN,true),0);server.start();
        assertEquals(303, post("/products/create", Map.of("sku", "NEW", "name", "New item", "category", "STORAGE",
                "price", "25.50", "reorderLevel", "2", "capacityGb", "128", "storageInterface", "USB-C", "supportedDevices", "Laptop")).statusCode());
        UUID receipt = UUID.randomUUID();
        Map<String, String> fields = Map.of("sku", "NEW", "quantity", "10", "reason", "Delivery", "requestId", receipt.toString());
        assertEquals(303, post("/stock/receive", fields).statusCode());
        assertEquals(303, post("/stock/receive", fields).statusCode());
        assertEquals(303, post("/stock/sell", Map.of("sku", "NEW", "quantity", "3", "reason", "Sale",
                "requestId", UUID.randomUUID().toString())).statusCode());
        assertEquals(303, post("/products/update", Map.of("sku", "NEW", "name", "Renamed", "category", "STORAGE",
                "price", "30.00", "reorderLevel", "8", "capacityGb", "256", "storageInterface", "USB-C", "supportedDevices", "Laptop")).statusCode());
        var stock = inventory.getStock(DemoData.MANAGER, "NEW");
        assertEquals(7, stock.quantity());
        assertEquals("Renamed", stock.product().name());
        assertEquals(new BigDecimal("30.00"), stock.product().sellingPrice());
        assertTrue(stock.isLowStock());
        assertEquals(2, inventory.history(DemoData.MANAGER, "NEW").size());
    }

    @Test
    void oversaleReturnsConflictAndPreservesEnteredFields() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var response = post("/stock/sell", Map.of("sku", "VE-CHG-001", "quantity", "99", "reason", "Test oversale", "requestId", requestId));
        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("Insufficient stock"));
        assertTrue(response.body().contains("value='99'"));
        assertTrue(response.body().contains("value='Test oversale'"));
        assertTrue(response.body().contains("value='" + requestId + "'"));
        assertEquals(4, inventory.getStock(DemoData.MANAGER, "VE-CHG-001").quantity());
    }

    @Test
    void filtersSearchCategoryAndLowStock() throws Exception {
        String search = catalogue(get("/?q=keyboard").body());
        assertTrue(search.contains("Showing 1 of 6 products"));
        assertTrue(search.contains("Wireless compact keyboard"));
        assertFalse(search.contains("65W USB-C fast charger"));
        String low = catalogue(get("/?status=low&category=Phone+accessories").body());
        assertTrue(low.contains("Showing 1 of 6 products"));
        assertTrue(low.contains("Clear phone case"));
        assertFalse(low.contains("Wireless compact keyboard"));
        assertTrue(get("/?q=512").body().contains("Showing 1 of 6 products"));
    }

    @Test
    void rejectsForgedFormsAndInvalidInput() throws Exception {
        server.close();server=new LocalPreviewServer(inventory,new com.inventory.security.User(UUID.randomUUID(),"admin",com.inventory.security.Role.ADMIN,true),0);server.start();
        assertEquals(403, rawPost("/stock/receive", "csrf=invalid&sku=VE-CHG-001&quantity=10").statusCode());
        var response = post("/products/create", Map.of("sku", "NEW", "name", "Keep my input", "category", "CHARGING",
                "price", "1.001", "reorderLevel", "2", "connector", "USB-C", "wattage", "65", "compatibility", "Laptops"));
        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("value='Keep my input'"));
        assertEquals(6, inventory.listProducts(DemoData.MANAGER).size());
        var oversized = HttpRequest.newBuilder(uri("/stock/receive"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(17000))).build();
        assertEquals(413, client.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void deactivationRequiresZeroStockAndRetainsHistory() throws Exception {
        assertEquals(409, post("/products/deactivate", Map.of("sku", "VE-CHG-001")).statusCode());
        assertEquals(303, post("/stock/sell", Map.of("sku", "VE-CHG-001", "quantity", "4", "reason", "Sell remaining",
                "requestId", UUID.randomUUID().toString())).statusCode());
        assertEquals(303, post("/products/deactivate", Map.of("sku", "VE-CHG-001")).statusCode());
        assertFalse(inventory.getStock(DemoData.MANAGER, "VE-CHG-001").product().active());
        assertEquals(3, inventory.history(DemoData.MANAGER, "VE-CHG-001").size());
    }

    @Test
    void exportQuotesValuesAndNeutralizesSpreadsheetFormulas() throws Exception {
        inventory.addProduct(DemoData.MANAGER, new Product("CSV", "=1+1", "Office, supplies", BigDecimal.ONE, 0, true));
        var response = get("/export/inventory.csv");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains("inventory.csv"));
        assertTrue(response.body().contains("\"'=1+1\",\"Office, supplies\""));
    }

    @Test
    void cashierCannotSubmitManagerActions() throws Exception {
        server.close();
        var cashier = new com.inventory.security.User(UUID.randomUUID(), "cashier", com.inventory.security.Role.STAFF, true);
        server = new LocalPreviewServer(inventory, cashier, 0);
        server.start();
        String html = get("/app").body();
        assertFalse(html.contains("action='/stock/receive'"));
        assertFalse(html.contains("action='/products/create'"));
        assertFalse(html.contains("action='/stock/sell'"));
        assertEquals(403, rawPost("/stock/receive", "sku=VE-CHG-001&quantity=1&reason=Receipt&requestId="+UUID.randomUUID()).statusCode());
        assertEquals(4, inventory.getStock(DemoData.MANAGER, "VE-CHG-001").quantity());
    }

    private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        String html = get("/app").body();
        var token = Pattern.compile("name='csrf' value='([^']+)'").matcher(html);
        assertTrue(token.find());
        Map<String, String> data = new HashMap<>(fields);
        data.put("csrf", token.group(1));
        String body = data.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        return rawPost(path, body);
    }

    @Test
    void categoryPickerShowsOnlyRelevantCreateFields() throws Exception {
        server.close();server=new LocalPreviewServer(inventory,new com.inventory.security.User(UUID.randomUUID(),"admin",com.inventory.security.Role.ADMIN,true),0);server.start();
        String page = get("/?newCategory=STORAGE").body();
        String createForm = page.substring(page.indexOf("<form class='product-form'"));
        createForm = createForm.substring(0, createForm.indexOf("</form>"));
        assertTrue(createForm.contains("name='capacityGb'"));
        assertTrue(createForm.contains("name='storageInterface'"));
        assertFalse(createForm.contains("name='wattage'"));
        assertFalse(createForm.contains("name='batteryHours'"));
        assertTrue(page.contains("VoltEdge Electronics"));
    }

    @Test
    void invalidCategoryOrMissingSpecificationsCannotCreateProduct() throws Exception {
        server.close();server=new LocalPreviewServer(inventory,new com.inventory.security.User(UUID.randomUUID(),"admin",com.inventory.security.Role.ADMIN,true),0);server.start();
        Map<String, String> common = Map.of("sku", "BAD", "name", "Missing specs", "category", "STORAGE",
                "price", "100", "reorderLevel", "1");
        assertEquals(422, post("/products/create", common).statusCode());
        Map<String, String> invalid = new HashMap<>(common);
        invalid.put("category", "Stationery");
        assertEquals(422, post("/products/create", invalid).statusCode());
        invalid.put("category", "LEGACY");
        assertEquals(422, post("/products/create", invalid).statusCode());
        assertEquals(6, inventory.listProducts(DemoData.MANAGER).size());
    }

    @Test
    void legacyRecordsRemainAccessibleOutsideDefaultElectronicsView() throws Exception {
        inventory.addProduct(DemoData.MANAGER, new Product("OLD", "Legacy notebook", "Stationery", BigDecimal.ONE, 0, true));
        String defaultPage = get("/app").body();
        assertFalse(defaultPage.contains("Legacy notebook"));
        String legacyPage = get("/?scope=legacy").body();
        assertTrue(legacyPage.contains("Legacy notebook"));
        assertTrue(legacyPage.contains("Showing 1 of 1 products"));
        var response = post("/products/update", Map.of("sku", "OLD", "name", "Converted SSD", "category", "STORAGE",
                "price", "100", "reorderLevel", "2", "capacityGb", "128", "storageInterface", "USB", "supportedDevices", "Laptop"));
        assertEquals(303, response.statusCode());
        assertInstanceOf(com.inventory.model.details.StorageDetails.class, inventory.getStock(DemoData.MANAGER, "OLD").product().details());
        assertTrue(get("/app").body().contains("Converted SSD"));
    }

    private HttpResponse<String> rawPost(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String catalogue(String page) {
        String section = page.substring(page.indexOf("<section id=\"catalogue\""));
        return section.substring(0, section.indexOf("</section>"));
    }

    @Test
    void dashboardQuickActionsFollowTheRoleWorkspace() throws Exception {
        String managerPage = get("/app").body();
        String managerActions = quickActions(managerPage);
        assertTrue(managerActions.contains("Inventory"));
        assertTrue(managerActions.contains("New sale"));
        assertTrue(managerActions.contains("Stock alerts"));
        assertFalse(managerActions.contains("Add product"));
        assertFalse(managerActions.contains("Create order"));
        assertFalse(managerActions.contains("Add supplier"));
        assertFalse(managerActions.contains("Receive delivery"));
        server.close();
        var admin = new com.inventory.security.User(UUID.randomUUID(), "admin", com.inventory.security.Role.ADMIN, true);
        server = new LocalPreviewServer(inventory, admin, 0);
        server.start();
        String adminActions = quickActions(get("/app").body());
        assertTrue(adminActions.contains("Inventory"));
        assertTrue(adminActions.contains("Add product"));
        assertTrue(adminActions.contains("Create order"));
        assertTrue(adminActions.contains("Add supplier"));
        assertTrue(adminActions.contains("Receive delivery"));
        assertFalse(adminActions.contains("New sale"));
    }

    private String quickActions(String page) {
        int start = page.indexOf("<div class='quick-action-grid'>");
        assertTrue(start >= 0);
        return page.substring(start, page.indexOf("</div></section>", start));
    }

    @Test
    void dashboardAssetsLoadLocallyWithoutExposingArbitraryFiles() throws Exception {
        var css = get("/assets/dashboard.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("Content-Type").orElseThrow().startsWith("text/css"));
        var image = client.send(HttpRequest.newBuilder(uri("/assets/conveyor-hero.png")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, image.statusCode());
        assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, java.util.Arrays.copyOf(image.body(), 8));
        assertEquals(404, get("/assets/pom.xml").statusCode());
    }

    @Test
    void purchaseHttpWorkflowRequiresAnotherReviewerAndSupportsPartialReceipts() throws Exception {
        var offer = inventory.operations().snapshot(DemoData.MANAGER).offers().stream()
                .filter(value -> value.sku().equals("VE-CHG-001")).findFirst().orElseThrow();
        UUID id = UUID.randomUUID();
        assertEquals(303, post("/purchase/create", Map.of("requestId", id.toString(), "offerId", offer.id().toString(), "quantity", "5", "note", "Restock chargers")).statusCode());
        assertEquals(com.inventory.model.procurement.OrderStatus.PENDING_APPROVAL, inventory.operations().snapshot(DemoData.MANAGER).orders().stream().filter(order -> order.id().equals(id)).findFirst().orElseThrow().status());
        assertEquals(409, post("/purchase/action", Map.of("orderId", id.toString(), "action", "APPROVE", "note", "Own approval")).statusCode());
        assertEquals(4, inventory.getStock(DemoData.MANAGER, "VE-CHG-001").quantity());
        server.close();
        server = new LocalPreviewServer(inventory, DemoData.APPROVER, 0);
        server.start();
        assertEquals(303, post("/purchase/action", Map.of("orderId", id.toString(), "action", "APPROVE", "note", "Approved independently")).statusCode());
        UUID receipt = UUID.randomUUID();
        Map<String, String> received = Map.of("orderId", id.toString(), "requestId", receipt.toString(), "quantity", "2", "note", "Partial delivery");
        assertEquals(303, post("/purchase/receive", received).statusCode());
        assertEquals(303, post("/purchase/receive", received).statusCode());
        var invalid = post("/purchase/receive", Map.of("orderId", id.toString(), "requestId", UUID.randomUUID().toString(), "quantity", "4", "note", "Too much"));
        assertEquals(409, invalid.statusCode());
        assertTrue(invalid.body().contains("value='4'"));
        assertTrue(invalid.body().contains("value='Too much'"));
        assertEquals(6, inventory.getStock(DemoData.MANAGER, "VE-CHG-001").quantity());
        assertEquals(303, post("/purchase/receive", Map.of("orderId", id.toString(), "requestId", UUID.randomUUID().toString(), "quantity", "3", "note", "Final delivery")).statusCode());
        assertEquals(9, inventory.getStock(DemoData.MANAGER, "VE-CHG-001").quantity());
        assertTrue(get("/?order=" + id).body().contains("RECEIVED"));
    }

    @Test
    void demoIdentitySwitchIsExplicitAndDoesNotPretendToAuthenticate() throws Exception {
        assertEquals(403, post("/demo/actor", Map.of("actor", "approver")).statusCode());
        server.close();
        server = new LocalPreviewServer(inventory, DemoData.MANAGER, 0, true);
        server.start();
        var changed = post("/demo/actor", Map.of("actor", "cashier"));
        assertEquals(303, changed.statusCode());
        assertTrue(changed.headers().firstValue("Set-Cookie").orElseThrow().contains("SameSite=Strict"));
        var request = HttpRequest.newBuilder(uri("/app")).header("Cookie", "demo_actor=cashier").GET().build();
        String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(body.contains("Demo identity (not a login)"));
        assertFalse(body.contains("Sales &amp; inventory analytics"));
        assertFalse(body.contains("action='/products/create'"));
    }

    @Test
    void analyticsRejectInvalidDatesAndExplainLimitedHistory() throws Exception {
        assertEquals(400, get("/?from=2026-11-10&to=2026-11-09").statusCode());
        assertEquals(400, get("/?from=not-a-date").statusCode());
        String page = get("/app").body();
        assertTrue(page.contains("threshold-based fallback"));
        assertTrue(page.contains("not acquisition cost"));
        assertTrue(page.contains("Send reorder request to Admin"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
