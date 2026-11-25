package com.inventory.database;

import com.inventory.preview.ServerSettings;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigurationTest {
    @Test void productionNeverFallsBackToLocalStorage() {
        assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(Map.of("INVENTORY_ENV","production"),"data/inventory"));
        try(var database=Database.fromEnvironment(Map.of(),"data/inventory")){assertFalse(database.postgres());assertEquals("H2",database.label());}
    }
    @Test void cloudConnectionsRequireCredentialsAndTls() {
        for(String url:java.util.List.of("jdbc:postgresql://db.example.com/inventory","jdbc:postgresql://db.example.com/inventory?sslmode=disable","jdbc:mysql://localhost/inventory"))
            assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(Map.of("INVENTORY_JDBC_URL",url),"unused"));
        assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(Map.of("INVENTORY_JDBC_URL","jdbc:postgresql://db.example.com/inventory?sslmode=verify-full&password=secret"),"unused"));
        assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(Map.of("INVENTORY_JDBC_URL","jdbc:postgresql://db.example.com/inventory?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory"),"unused"));
    }
    @Test void requireAndVerifyFullPassTlsValidationButStillRequireCredentials() {
        for(String mode:java.util.List.of("require","verify-full")) {
            var error=assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(
                    Map.of("INVENTORY_JDBC_URL","jdbc:postgresql://db.example.com/inventory?sslmode="+mode),"unused"));
            assertEquals("Set INVENTORY_DB_USER and INVENTORY_DB_PASSWORD",error.getMessage());
        }
        var error=assertThrows(IllegalArgumentException.class,()->Database.fromEnvironment(
                Map.of("INVENTORY_JDBC_URL","jdbc:postgresql://db.example.com/inventory?sslmode=require-invalid"),"unused"));
        assertEquals("PostgreSQL JDBC URL must use sslmode=require or sslmode=verify-full",error.getMessage());
    }
    @Test void hostedOriginIsExplicitAndCookiesAreSecure() {
        var settings=new ServerSettings("0.0.0.0","https://inventory.example.com");
        assertTrue(settings.acceptsHost("inventory.example.com",8080));assertFalse(settings.acceptsHost("attacker.example",8080));
        assertTrue(settings.acceptsOrigin("https://inventory.example.com",8080));assertFalse(settings.acceptsOrigin("http://inventory.example.com",8080));
        assertTrue(settings.cookieFlags().contains("Secure"));
        assertThrows(IllegalArgumentException.class,()->new ServerSettings("0.0.0.0",""));
        assertThrows(IllegalArgumentException.class,()->new ServerSettings("0.0.0.0","http://inventory.example.com"));
        assertFalse(ServerSettings.local().secureCookies());
    }
}
