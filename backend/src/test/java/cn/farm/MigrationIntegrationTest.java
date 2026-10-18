package cn.farm;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationIntegrationTest {
    @Test void upgradingPopulatedV3PreservesRecordsSnapshotsPaymentsAndUniqueKey()throws Exception{
        String source=System.getenv("TEST_DB_URL");
        boolean mysql="jdbc:mysql://127.0.0.1:13317/harvest_test?serverTimezone=Asia/Shanghai".equals(source)
            && System.getenv("REGRESSION_REPORT_DIR")!=null;
        String url=mysql?source.replace("/harvest_test?","/harvest_migration_test?")
            :"jdbc:h2:mem:migration_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        String user=mysql?"root":"sa";
        Flyway.configure().dataSource(url,user,"").target("3").load().migrate();
        try(var connection=DriverManager.getConnection(url,user,"");var statement=connection.createStatement()){
            statement.executeUpdate("INSERT INTO harvest_record(id,worker_id,work_date,period,product_id,inputs_json,note,created_by,updated_by,deleted) VALUES('grouped','w','2025-01-10','AM','p','{\"quantities\":{\"main\":5}}','old','u','u',FALSE),('legacy','w','2025-01-10','PM','p','{\"quantities\":{\"main\":2}}','old','u','u',TRUE)");
            statement.executeUpdate("INSERT INTO record_group(record_id,group_id,group_name,inputs_json) VALUES('grouped','g','旧班组','{\"quantities\":{\"main\":5}}')");
            statement.executeUpdate("INSERT INTO settlement(id,month_key,kind,created_by) VALUES('s','2025-01','MONTHLY','u')");
            statement.executeUpdate("INSERT INTO settlement_line(id,settlement_id,record_id,worker_id,amount,snapshot_json) VALUES('line','s','grouped','w',15.00,'immutable-snapshot')");
            statement.executeUpdate("INSERT INTO payment(id,worker_id,month_key,amount,method,note,created_by) VALUES('pay','w','2025-01',10.00,'cash','old','u')");
            statement.executeUpdate("INSERT INTO basket_type(id,name,weight_kg,active) VALUES('basket','旧筐',1.25,TRUE)");
            statement.executeUpdate("INSERT INTO daily_signature(worker_id,work_date,fingerprint,snapshot_json,signed_by) VALUES('w','2025-01-10','fingerprint','signed-snapshot','u')");
        }
        var migrated=Flyway.configure().dataSource(url,user,"").load();migrated.migrate();migrated.validate();
        try(var connection=DriverManager.getConnection(url,user,"");var statement=connection.createStatement()){
            try(var rows=statement.executeQuery("SELECT * FROM record_plot ORDER BY record_id")){
                assertTrue(rows.next());assertEquals("grouped",rows.getString("record_id"));assertEquals("g",rows.getString("group_id"));assertEquals("",rows.getString("plot_id"));assertTrue(rows.getString("inputs_json").contains("5"));
                assertTrue(rows.next());assertEquals("legacy",rows.getString("record_id"));assertEquals("",rows.getString("group_id"));assertTrue(rows.getString("inputs_json").contains("2"));assertFalse(rows.next());
            }
            try(var rows=statement.executeQuery("SELECT snapshot_json,amount FROM settlement_line")){assertTrue(rows.next());assertEquals("immutable-snapshot",rows.getString(1));assertEquals(0,rows.getBigDecimal(2).compareTo(new java.math.BigDecimal("15")));}
            try(var rows=statement.executeQuery("SELECT amount FROM payment")){assertTrue(rows.next());assertEquals(0,rows.getBigDecimal(1).compareTo(java.math.BigDecimal.TEN));}
            try(var rows=statement.executeQuery("SELECT snapshot_json FROM daily_signature")){assertTrue(rows.next());assertEquals("signed-snapshot",rows.getString(1));}
            try(var rows=statement.executeQuery("SELECT weight_kg,revision FROM weight_preset_revision WHERE preset_id='basket' AND kind='BASKET'")){assertTrue(rows.next());assertEquals(1,rows.getInt("revision"));assertEquals(0,rows.getBigDecimal("weight_kg").compareTo(new java.math.BigDecimal("1.25")));}
            assertThrows(SQLException.class,()->statement.executeUpdate("INSERT INTO harvest_record(id,worker_id,work_date,period,product_id,inputs_json,note,created_by,updated_by) VALUES('duplicate','w','2025-01-10','PM','p','{}','','u','u')"));
        }
        assertEquals(0,migrated.migrate().migrationsExecuted);
    }
}
