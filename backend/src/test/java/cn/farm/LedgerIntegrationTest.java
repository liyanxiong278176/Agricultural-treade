package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:farm;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000}","spring.datasource.username=${TEST_DB_USER:sa}","spring.datasource.password=","farm.demo=false"})
@AutoConfigureMockMvc
class LedgerIntegrationTest {
    @Autowired Store db;@Autowired Ledger ledger;@Autowired Catalog catalog;@Autowired Pricing pricing;@Autowired Payroll payroll;@Autowired Auth auth;@Autowired MockMvc mvc;@Autowired DailyReview daily;
    Actor admin,recorder,other;String worker,product;LocalDate date;
    @BeforeEach void setup(){
        String database=db.jdbc.queryForObject("SELECT DATABASE()",String.class);
        if(!database.equalsIgnoreCase("farm")&&!database.equalsIgnoreCase("harvest_test"))throw new IllegalStateException("Tests may only reset farm or harvest_test");
        for(String table:List.of("weight_preset_revision","gross_weight","dispatch_receipt","dispatch_plan","import_batch","daily_print_snapshot","record_plot","farm_plot","daily_signature","record_group","print_template","basket_type","farm_group","operation_receipt","settlement_line","settlement","payment","audit_log","harvest_record","ledger_month","price_rule","product","category","worker","auth_session","farm_user"))db.jdbc.update("DELETE FROM "+table);
        new DemoData(db,true).run(null);db.jdbc.update("DELETE FROM harvest_record");db.jdbc.update("DELETE FROM audit_log");
        var users=db.rows("SELECT * FROM farm_user");admin=actor(users,"admin");recorder=actor(users,"recorder");other=actor(users,"recorder2");worker=str(db.one("SELECT * FROM worker WHERE code='W001'"),"id");product=str(db.one("SELECT * FROM product WHERE name='番茄'"),"id");date=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }
    Actor actor(List<Map<String,Object>> users,String username){var u=users.stream().filter(r->str(r,"username").equals(username)).findFirst().orElseThrow();return new Actor(str(u,"id"),username,str(u,"display_name"),str(u,"role"));}
    Inputs input(String quantity){return new Inputs(Map.of("main",new BigDecimal(quantity)),null,"kg");}
    String group(String name){return str((Map<String,Object>)catalog.save("groups",Map.of("name",name),admin),"id");}
    Operation quantity(String record,String action,String mode,String group,Inputs input,Integer version,String reason){return new Operation(id(),action,record,worker,date,"AM",product,input,"",version,reason,new Entry(mode,group,input));}
    @SuppressWarnings("unchecked") Map<String,Object> dailyWorker(){return ((List<Map<String,Object>>)((Map<String,Object>)daily.day(date,admin)).get("workers")).stream().filter(w->worker.equals(w.get("workerId"))).findFirst().orElseThrow();}

    @Test void groupedAddAndCorrectionKeepOneRecordAndRetryDoesNotDoubleAdd(){
        String a=group("一组"),b=group("二组"),record=id();
        ledger.apply(quantity(record,"CREATE","ADD",a,input("2"),null,""),recorder);
        var add=quantity(record,"UPDATE","ADD",b,input("3"),1,"");ledger.apply(add,other);ledger.apply(add,other);
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM harvest_record",Integer.class));
        assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM record_group",Integer.class));
        decimal("5",db.json.read(str(ledger.record(record),"inputs_json"),Inputs.class).quantities().get("main"));
        assertEquals("VERSION_CONFLICT",ledger.apply(quantity(record,"UPDATE","ADD",a,input("1"),1,""),other).status());
        decimal("5",db.json.read(str(ledger.record(record),"inputs_json"),Inputs.class).quantities().get("main"));
        assertThrows(Problem.class,()->ledger.apply(quantity(record,"UPDATE","CORRECT",b,input("1.5"),2,""),recorder));
        ledger.apply(quantity(record,"UPDATE","CORRECT",b,input("1.5"),2,"核对二组数量"),recorder);
        decimal("3.5",db.json.read(str(ledger.record(record),"inputs_json"),Inputs.class).quantities().get("main"));
        var before=db.one("SELECT * FROM audit_log WHERE action='CORRECT_QUANTITY'");assertTrue(str(before,"before_json").contains("groups"));assertTrue(str(before,"after_json").contains("quantity_operation"));
        catalog.save("workers",Map.of("id",worker,"code","W001","name","改组工人","groupId",b),admin);
        assertEquals(a,str(db.one("SELECT * FROM record_group WHERE record_id=? AND group_id=?",record,a),"group_id"));
    }
    @Test void groupWagesRoundAtPeriodAndLegacyQuantityBecomesUnassigned(){
        catalog.publish(product,date,new Rule("SEGMENTS",List.of(new Stage("main","称重","WEIGHT",new BigDecimal("0.35"),null,null,null,null))),"称重测试",admin);
        String a=group("一组"),b=group("二组"),record=id();
        ledger.apply(quantity(record,"CREATE","ADD",a,input("1.5"),null,""),recorder);
        ledger.apply(quantity(record,"UPDATE","ADD",b,new Inputs(Map.of("main",new BigDecimal("3")),null,"jin"),1,""),recorder);
        assertEquals(new BigDecimal("1.05"),((Quote)ledger.enrich(ledger.record(record)).get("quote")).amount());
        assertEquals(1,((List<?>)((Map<?,?>)daily.day(date,admin)).get("workers")).size());
    }
    @Test void grossAndBasketTareAreRecomputedOnServerAndInvalidTareRollsBack(){
        catalog.publish(product,date,new Rule("SEGMENTS",List.of(new Stage("main","称重","WEIGHT",BigDecimal.ONE,null,null,null,null))),"称重测试",admin);
        String basket=str((Map<String,Object>)catalog.save("baskets",Map.of("name","大筐","weightKg","1"),admin),"id");
        Inputs gross=new Inputs(Map.of("main",new BigDecimal("999")),null,"jin",Map.of("main",new Weighing(new BigDecimal("32"),null,basket,new BigDecimal("2"),3)));
        String record=id();ledger.apply(quantity(record,"CREATE","ADD","",gross,null,""),recorder);
        decimal("13",db.json.read(str(ledger.record(record),"inputs_json"),Inputs.class).quantities().get("main"));
        catalog.save("baskets",Map.of("id",basket,"name","大筐","weightKg","2"),admin);
        decimal("13",db.json.read(str(ledger.record(record),"inputs_json"),Inputs.class).quantities().get("main"));
        Inputs bad=new Inputs(Map.of(),null,"kg",Map.of("main",new Weighing(BigDecimal.ONE,new BigDecimal("2"),null,null,null)));
        assertThrows(Problem.class,()->ledger.apply(quantity(record,"UPDATE","ADD","",bad,1,""),recorder));
        assertEquals(1,((Number)ledger.record(record).get("version")).intValue());
        decimal("13",db.json.read(str(ledger.record(record),"inputs_json"),Inputs.class).quantities().get("main"));
    }
    @Test void oldRecordGroupMigrationAndFailedOperationLeaveNoAllocationChanges(){
        var op=create("5");ledger.apply(op,recorder);String a=group("新班组");
        ledger.apply(quantity(op.recordId(),"UPDATE","ADD",a,input("2"),1,""),recorder);
        var allocations=db.rows("SELECT * FROM record_group WHERE record_id=?",op.recordId());assertEquals(2,allocations.size());
        decimal("5",db.json.read(str(allocations.stream().filter(g->str(g,"group_id").isEmpty()).findFirst().orElseThrow(),"inputs_json"),Inputs.class).quantities().get("main"));
        assertThrows(Problem.class,()->ledger.apply(edit(op,2,"9"),recorder));
        decimal("7",db.json.read(str(ledger.record(op.recordId()),"inputs_json"),Inputs.class).quantities().get("main"));
    }
    @Test void signaturesRequireFreshSnapshotAndInvalidateAfterQuantityAndPriceChanges(){
        String g=group("一组"),record=id();ledger.apply(quantity(record,"CREATE","ADD",g,input("2"),null,""),recorder);
        String first=(String)dailyWorker().get("fingerprint");daily.sign(new DailyReview.Signature(worker,date,first),recorder);
        assertEquals("SIGNED",dailyWorker().get("status"));
        ledger.apply(quantity(record,"UPDATE","ADD",g,input("1"),1,""),other);
        assertEquals("RESIGN",dailyWorker().get("status"));
        assertThrows(Problem.class,()->daily.sign(new DailyReview.Signature(worker,date,first),recorder));
        daily.sign(new DailyReview.Signature(worker,date,(String)dailyWorker().get("fingerprint")),recorder);
        catalog.publish(product,date,new Rule("SEGMENTS",List.of(new Stage("main","采摘筐数","PIECE",new BigDecimal("4"),null,null,null,null))),"调价",admin);
        assertEquals("RESIGN",dailyWorker().get("status"));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM payment",Integer.class));
        assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='DAILY_SIGN'",Integer.class));
    }
    @Test void confirmingPayrollDoesNotInvalidateUnchangedPaperSignature(){
        String g=group("一组"),record=id();ledger.apply(quantity(record,"CREATE","ADD",g,input("2"),null,""),recorder);
        daily.sign(new DailyReview.Signature(worker,date,(String)dailyWorker().get("fingerprint")),recorder);
        payroll.settle(date.toString().substring(0,7),admin);assertEquals("SIGNED",dailyWorker().get("status"));
        ledger.apply(quantity(record,"UPDATE","CORRECT",g,input("2"),1,"复核数量未变"),admin);assertEquals("SIGNED",dailyWorker().get("status"));
    }
    @Test void groupedSettledRecordStillNeedsAdminCorrectionAndSupplement(){
        String g=group("一组"),record=id();ledger.apply(quantity(record,"CREATE","ADD",g,input("2"),null,""),recorder);payroll.settle(date.toString().substring(0,7),admin);
        assertThrows(Problem.class,()->ledger.apply(quantity(record,"UPDATE","ADD",g,input("1"),1,""),recorder));
        assertThrows(Problem.class,()->ledger.apply(quantity(record,"UPDATE","CORRECT",g,input("3"),1,""),admin));
        ledger.apply(quantity(record,"UPDATE","CORRECT",g,input("3"),1,"已结算核对更正"),admin);
        decimal("3",wages(date.toString().substring(0,7),worker).get("pending"));
        decimal("6",db.jdbc.queryForObject("SELECT SUM(amount) FROM settlement_line",BigDecimal.class));
    }
    @Test void printTemplateAndHistoryRespectRolesAndOldOperationHashShape()throws Exception{
        var old=create("2");String json=db.json.write(old);assertFalse(json.contains("entry"));assertFalse(json.contains("weighings"));
        assertThrows(Problem.class,()->daily.saveTemplate(new DailyReview.Template("表格",List.of("name","signature"),12,130),recorder));
        assertThrows(Problem.class,()->daily.saveTemplate(new DailyReview.Template("表格",List.of("name"),12,130),admin));
        daily.saveTemplate(new DailyReview.Template("农场核对",List.of("name","code","total","signature"),14,160),admin);assertEquals("农场核对",daily.template().title());
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> wages(String month,String workerId){return ((Collection<Map<String,Object>>)payroll.preview(month).get("workers")).stream().filter(w->workerId.equals(w.get("workerId"))).findFirst().orElseThrow();}
    void decimal(String expected,Object actual){assertEquals(0,new BigDecimal(expected).compareTo((BigDecimal)actual));}

    @Test void incompleteRulesReturnBadRequestForTrialAndPublication()throws Exception{
        String token=(String)auth.login("admin","Farm@2026").get("token");
        var invalid=List.of("null","{}","{\"mode\":\"SEGMENTS\"}","{\"mode\":\"UNKNOWN\",\"stages\":[]}",
            "{\"mode\":\"SEGMENTS\",\"stages\":[null]}","{\"mode\":\"SEGMENTS\",\"stages\":[{}]}",
            "{\"stages\":[{\"key\":\"main\",\"label\":\"筐数\",\"type\":\"PIECE\",\"rate\":3}]}",
            "{\"mode\":\"SEGMENTS\",\"stages\":[{\"key\":\"main\",\"label\":\"筐数\",\"rate\":3}]}",
            "{\"mode\":\"SEGMENTS\",\"stages\":[{\"key\":\"main\",\"label\":\"筐数\",\"type\":\"PIECE\"}]}");
        int rulesBefore=db.jdbc.queryForObject("SELECT COUNT(*) FROM price_rule",Integer.class);
        for(String rule:invalid){
            String trial="{\"rule\":"+rule+",\"inputs\":{\"quantities\":{\"main\":5},\"weightUnit\":\"kg\"}}";
            String publish="{\"productId\":\""+product+"\",\"effectiveDate\":\""+date+"\",\"rule\":"+rule+",\"reason\":\"校验测试\"}";
            for(var entry:Map.of("/api/rules/trial",trial,"/api/rules",publish).entrySet())
                mvc.perform(post(entry.getKey()).header("Authorization","Bearer "+token).contentType("application/json").content(entry.getValue())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        }
        assertEquals(rulesBefore,db.jdbc.queryForObject("SELECT COUNT(*) FROM price_rule",Integer.class));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
    }

    @Test void sqlPayrollGroupsWorkersAndUnitsWithoutDeletedOrOtherMonthWork(){
        String month=date.toString().substring(0,7), otherWorker=str(db.one("SELECT * FROM worker WHERE code='W002'"),"id");
        String weightProduct=str(db.one("SELECT * FROM product WHERE name='小白菜'"),"id");
        var first=create("5");ledger.apply(first,recorder);
        ledger.apply(new Operation(id(),"CREATE",id(),worker,date,"PM",product,input("2"),"",null,""),recorder);
        ledger.apply(new Operation(id(),"CREATE",id(),otherWorker,date,"AM",product,input("1.5"),"",null,""),other);
        ledger.apply(new Operation(id(),"CREATE",id(),worker,date,"AM",weightProduct,new Inputs(Map.of("main",new BigDecimal("20")),null,"jin"),"",null,""),recorder);
        var deleted=new Operation(id(),"CREATE",id(),worker,date,"PM",weightProduct,input("7"),"",null,"");
        ledger.apply(deleted,recorder);ledger.apply(new Operation(id(),"DELETE",deleted.recordId(),worker,date,"PM",weightProduct,null,"",1,"误记"),recorder);
        LocalDate previous=date.withDayOfMonth(1).minusDays(1);
        catalog.publish(product,previous.withDayOfMonth(1),catalog.rule(product,date),"往月测试","MONTH",admin);
        ledger.apply(new Operation(id(),"CREATE",id(),worker,previous,"AM",product,input("11"),"往月补录",null,""),recorder);
        var current=wages(month,worker);decimal("7",current.get("pieces"));decimal("10",current.get("weightKg"));decimal("26",current.get("earned"));
        decimal("1.5",wages(month,otherWorker).get("pieces"));decimal("4.50",wages(month,otherWorker).get("earned"));
        decimal("11",wages(previous.toString().substring(0,7),worker).get("pieces"));
        payroll.settle(month,admin);ledger.apply(edit(first,1,"6"),admin);payroll.settle(month,admin);
        payroll.pay(id(),worker,month,new BigDecimal("10"),"现金","",admin);
        current=wages(month,worker);decimal("8",current.get("pieces"));decimal("29",current.get("earned"));decimal("29",current.get("settled"));decimal("0",current.get("pending"));decimal("10",current.get("paid"));decimal("19",current.get("unpaid"));
        catalog.publish(product,date.withDayOfMonth(1),new Rule("SEGMENTS",List.of(new Stage("main","筐数","PIECE",new BigDecimal("4"),null,null,null,null))),"追溯涨价","MONTH",admin);
        current=wages(month,worker);decimal("37",current.get("earned"));decimal("29",current.get("settled"));decimal("8",current.get("pending"));
        assertTrue(((Collection<?>)payroll.preview("1999-01").get("workers")).isEmpty());
    }

    @Test void sqlPayrollPreservesAllocationAndPerRecordRounding(){
        String month=date.toString().substring(0,7);
        var allocation=new Rule("ALLOCATE",List.of(new Stage("piece","计件","PIECE",new BigDecimal("3"),new BigDecimal("10"),null,null,null),new Stage("weight","余量","WEIGHT",new BigDecimal("0.35"),null,null,null,null)));
        catalog.publish(product,date,allocation,"组合计费",admin);
        ledger.apply(new Operation(id(),"CREATE",id(),worker,date,"AM",product,new Inputs(Map.of(),new BigDecimal("52"),"kg"),"",null,""),recorder);
        String weightProduct=str(db.one("SELECT * FROM product WHERE name='小白菜'"),"id");
        catalog.publish(weightProduct,date,new Rule("SEGMENTS",List.of(new Stage("main","称重","WEIGHT",new BigDecimal("0.35"),null,null,null,null))),"逐条舍入",admin);
        for(String period:List.of("AM","PM"))ledger.apply(new Operation(id(),"CREATE",id(),worker,date,period,weightProduct,new Inputs(Map.of("main",new BigDecimal("1500")),null,"g"),"",null,""),recorder);
        var current=wages(month,worker);decimal("5",current.get("pieces"));decimal("5",current.get("weightKg"));decimal("16.76",current.get("earned"));
        // Rounding the combined 3kg instead would produce 1.05, not two times 0.53.
        decimal("16.76",current.get("pending"));decimal("0",current.get("paid"));
    }
    Operation create(String quantity){return new Operation(id(),"CREATE",id(),worker,date,"AM",product,input(quantity),"",null,"");}
    Operation edit(Operation op,int version,String quantity){return new Operation(id(),"UPDATE",op.recordId(),worker,date,"AM",product,input(quantity),"核对数量",version,"增加采摘量");}
    @Test void operationRetryIsIdempotentAndPayloadBound(){var op=create("5");assertEquals("ACCEPTED",ledger.apply(op,recorder).status());assertEquals("ACCEPTED",ledger.apply(op,recorder).status());assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM harvest_record",Integer.class));assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));var changed=new Operation(op.operationId(),op.action(),op.recordId(),worker,date,"AM",product,input("9"),"",null,"");assertThrows(Problem.class,()->ledger.apply(changed,recorder));}
    @Test void businessKeyRejectsDuplicateButSeparatesPeriodsAndDays(){var op=create("5");ledger.apply(op,recorder);assertEquals("DUPLICATE_CONFLICT",ledger.apply(create("7"),other).status());var afternoon=new Operation(id(),"CREATE",id(),worker,date,"PM",product,input("2"),"",null,"");assertEquals("ACCEPTED",ledger.apply(afternoon,other).status());}
    @Test void optimisticLockKeepsLatestAndRequiresNewVersion(){var op=create("5");ledger.apply(op,recorder);assertEquals("ACCEPTED",ledger.apply(edit(op,1,"8"),recorder).status());var failed=ledger.apply(edit(op,1,"7"),other);assertEquals("VERSION_CONFLICT",failed.status());assertEquals(2,((Number)failed.record().get("version")).intValue());assertEquals("ACCEPTED",ledger.apply(edit(op,2,"7"),other).status());assertEquals(3,((Number)ledger.record(op.recordId()).get("version")).intValue());}
    @Test void deletionPreservesBusinessKeyAndRecorderCanRestore(){var op=create("5");ledger.apply(op,recorder);var del=new Operation(id(),"DELETE",op.recordId(),worker,date,"AM",product,null,"",1,"误记");ledger.apply(del,recorder);assertEquals("DELETED_CONFLICT",ledger.apply(create("6"),other).status());var restore=new Operation(id(),"RESTORE",op.recordId(),worker,date,"AM",product,null,"",2,"恢复");assertEquals("ACCEPTED",ledger.apply(restore,other).status());assertFalse(flag(ledger.record(op.recordId()).get("deleted")));}
    @Test void anotherRecorderCannotDelete(){var op=create("5");ledger.apply(op,recorder);assertThrows(Problem.class,()->ledger.apply(new Operation(id(),"DELETE",op.recordId(),worker,date,"AM",product,null,"",1,""),other));}
    @Test void settledRecordCannotBeDeletedByEitherRoleButCanBeCorrected(){
        var op=create("5");ledger.apply(op,recorder);String month=date.toString().substring(0,7);
        assertFalse(flag(ledger.list(recorder,month).getFirst().get("settled")));payroll.settle(month,admin);
        assertTrue(flag(ledger.list(recorder,month).getFirst().get("settled")));
        var deletion=new Operation(id(),"DELETE",op.recordId(),worker,date,"AM",product,null,"",1,"误记");
        for(var actor:List.of(recorder,admin))assertTrue(assertThrows(Problem.class,()->ledger.apply(deletion,actor)).getMessage().contains("不能直接删除"));
        assertFalse(flag(ledger.record(op.recordId()).get("deleted")));
        assertEquals("ACCEPTED",ledger.apply(edit(op,1,"4"),admin).status());
        assertEquals(new BigDecimal("15.00"),db.jdbc.queryForObject("SELECT SUM(amount) FROM settlement_line",BigDecimal.class));
        payroll.settle(month,admin);assertEquals(new BigDecimal("12.00"),db.jdbc.queryForObject("SELECT SUM(amount) FROM settlement_line",BigDecimal.class));
    }
    @Test void lateHistoricalRecordCanBeDeletedUntilItEntersSupplement(){
        ledger.apply(create("5"),recorder);String month=date.toString().substring(0,7);payroll.settle(month,admin);
        LocalDate prior=date.minusDays(1);String lateMonth=prior.toString().substring(0,7);db.lockMonth(lateMonth);db.jdbc.update("UPDATE ledger_month SET closed=TRUE WHERE month_key=?",lateMonth);
        var late=new Operation(id(),"CREATE",id(),worker,prior,"PM",product,input("2"),"后来补录",null,"");ledger.apply(late,recorder);
        assertFalse(flag(ledger.enrich(ledger.record(late.recordId())).get("settled")));
        var deletion=new Operation(id(),"DELETE",late.recordId(),worker,prior,"PM",product,null,"",1,"误记");
        assertThrows(Problem.class,()->ledger.apply(deletion,other));assertEquals("ACCEPTED",ledger.apply(deletion,recorder).status());
        assertEquals("ACCEPTED",ledger.apply(new Operation(id(),"RESTORE",late.recordId(),worker,prior,"PM",product,null,"",2,"恢复"),recorder).status());
        payroll.settle(lateMonth,admin);assertTrue(flag(ledger.enrich(ledger.record(late.recordId())).get("settled")));
        var after=new Operation(id(),"DELETE",late.recordId(),worker,prior,"PM",product,null,"",3,"误记");assertThrows(Problem.class,()->ledger.apply(after,recorder));assertThrows(Problem.class,()->ledger.apply(after,admin));
    }
    @Test void directSubmissionUsesSameIdempotencyAndValidationAsOfflineSync()throws Exception{
        String token=(String)auth.login("recorder","Farm@2026").get("token");var op=create("5");
        for(int retry=0;retry<2;retry++)mvc.perform(post("/api/records/operate").header("Authorization","Bearer "+token).contentType("application/json").content(db.json.write(op))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM harvest_record",Integer.class));
        payroll.settle(date.toString().substring(0,7),admin);
        var deletion=new Operation(id(),"DELETE",op.recordId(),worker,date,"AM",product,null,"",1,"误记");
        mvc.perform(post("/api/records/operate").header("Authorization","Bearer "+token).contentType("application/json").content(db.json.write(deletion))).andExpect(status().isConflict());
    }
    @Test void concurrentDeletionAndSettlementCannotDeleteConfirmedWork()throws Exception{
        var op=create("5");ledger.apply(op,recorder);String month=date.toString().substring(0,7);
        try(var pool=Executors.newFixedThreadPool(2)){
            var latch=new CountDownLatch(1);
            var settlement=pool.submit(()->{latch.await();try{payroll.settle(month,admin);return "SETTLED";}catch(Problem e){assertEquals("没有待结算差额",e.getMessage());return "EMPTY";}});
            var deletion=pool.submit(()->{latch.await();try{return ledger.apply(new Operation(id(),"DELETE",op.recordId(),worker,date,"AM",product,null,"",1,"误记"),recorder).status();}catch(Problem e){assertTrue(e.getMessage().contains("不能直接删除"));return "PROTECTED";}});
            latch.countDown();String confirmed=settlement.get(20,TimeUnit.SECONDS),removed=deletion.get(20,TimeUnit.SECONDS);
            boolean deleted=flag(ledger.record(op.recordId()).get("deleted"));
            assertEquals(deleted?"EMPTY":"SETTLED",confirmed);assertEquals(deleted?"ACCEPTED":"PROTECTED",removed);assertEquals(!deleted,ledger.settled(op.recordId()));
        }
    }
    @Test void settlementSnapshotsLateRecordAndPartialPayment(){
        var first=create("5");ledger.apply(first,recorder);payroll.settle(date.toString().substring(0,7),admin);var month=date.toString().substring(0,7);
        var late=new Operation(id(),"CREATE",id(),worker,date,"PM",product,input("2"),"离线晚传",null,"");assertEquals("ACCEPTED",ledger.apply(late,other).status());
        assertEquals(new BigDecimal("15.00"),db.jdbc.queryForObject("SELECT SUM(amount) FROM settlement_line",BigDecimal.class));payroll.settle(month,admin);assertEquals(new BigDecimal("21.00"),db.jdbc.queryForObject("SELECT SUM(amount) FROM settlement_line",BigDecimal.class));assertThrows(Problem.class,()->payroll.settle(month,admin));
        String pid=id();payroll.pay(pid,worker,month,new BigDecimal("10.00"),"现金","分次发放",admin);payroll.pay(pid,worker,month,new BigDecimal("10.00"),"现金","分次发放",admin);assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM payment",Integer.class));assertThrows(Problem.class,()->payroll.pay(id(),worker,month,new BigDecimal("12"),"现金","",admin));
    }
    @Test void roundPerRecordAndConvertUnits(){var rule=new Rule("SEGMENTS",List.of(new Stage("a","称重","WEIGHT",new BigDecimal("0.35"),null,null,null,null)));for(var entry:Map.of("kg","1.5","jin","3","g","1500").entrySet()){var result=pricing.calculate(rule,new Inputs(Map.of("a",new BigDecimal(entry.getValue())),null,entry.getKey()));assertEquals(new BigDecimal("0.53"),result.amount());}}
    @Test void halfBasketsOnly(){assertThrows(Problem.class,()->pricing.calculate(catalog.rule(product,date),input("1.2")));assertEquals(new BigDecimal("4.50"),pricing.calculate(catalog.rule(product,date),input("1.5")).amount());}
    @Test void configurablePriorityAllocatesOnlyRemainingWork(){var rule=new Rule("ALLOCATE",List.of(new Stage("piece","先计件","PIECE",new BigDecimal("3"),new BigDecimal("10"),null,null,null),new Stage("weight","余量计重","WEIGHT",new BigDecimal("0.5"),null,null,null,null)));var q=pricing.calculate(rule,new Inputs(Map.of(),new BigDecimal("52"),"kg"));assertEquals(new BigDecimal("16.00"),q.amount());assertEquals(new BigDecimal("5.0"),q.parts().getFirst().quantity());assertThrows(Problem.class,()->pricing.calculate(new Rule("ALLOCATE",List.of(rule.stages().getFirst())),new Inputs(Map.of(),new BigDecimal("52"),"kg")));}
    @Test void retroactivePriceProducesSupplementWithoutChangingSnapshot(){var op=create("5");ledger.apply(op,recorder);String month=date.toString().substring(0,7);payroll.settle(month,admin);var newRule=new Rule("SEGMENTS",List.of(new Stage("main","筐数","PIECE",new BigDecimal("4"),null,null,null,null)));catalog.publish(product,date.withDayOfMonth(1),newRule,"整月涨价",admin);payroll.settle(month,admin);assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM settlement_line",Integer.class));assertEquals(new BigDecimal("20.00"),db.jdbc.queryForObject("SELECT SUM(amount) FROM settlement_line",BigDecimal.class));}
    @Test void concurrentCreateHasOneWinner()throws Exception{try(var pool=Executors.newFixedThreadPool(2)){var latch=new CountDownLatch(1);Callable<String> task=()->{latch.await();return ledger.apply(create("5"),recorder).status();};var a=pool.submit(task);var b=pool.submit(task);latch.countDown();var result=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertEquals(1,result.stream().filter("ACCEPTED"::equals).count());assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM harvest_record",Integer.class));}}
    @Test void serverEnforcesRoleAndExportIsWorkbook()throws Exception{String token=(String)auth.login("recorder","Farm@2026").get("token");mvc.perform(get("/api/payroll").param("month",date.toString().substring(0,7)).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());mvc.perform(get("/api/bootstrap")).andExpect(status().isUnauthorized());String ownerToken=(String)auth.login("admin","Farm@2026").get("token");byte[] bytes=mvc.perform(get("/api/payroll/export").param("month",date.toString().substring(0,7)).header("Authorization","Bearer "+ownerToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();assertEquals('P',bytes[0]);assertEquals('K',bytes[1]);}
    @Test void monthScopedPriceDoesNotChangeOtherMonths(){var rule=new Rule("SEGMENTS",List.of(new Stage("main","筐数","PIECE",new BigDecimal("9"),null,null,null,null)));catalog.publish(product,date,rule,"指定月份调整","MONTH",admin);assertEquals(new BigDecimal("45.00"),pricing.calculate(catalog.rule(product,date),input("5")).amount());assertEquals(new BigDecimal("15.00"),pricing.calculate(catalog.rule(product,date.plusMonths(1)),input("5")).amount());}
    @Test void monthRepricingCannotReduceCompletedWorkOrBreakExistingInputs(){ledger.apply(create("5"),recorder);var cheaper=new Rule("SEGMENTS",List.of(new Stage("main","筐数","PIECE",new BigDecimal("1"),null,null,null,null)));assertThrows(Problem.class,()->catalog.publish(product,date,cheaper,"降价不能追溯","MONTH",admin));var incompatible=new Rule("SEGMENTS",List.of(new Stage("new_key","新录入项","PIECE",new BigDecimal("4"),null,null,null,null)));assertThrows(Problem.class,()->catalog.publish(product,date,incompatible,"变更结构","FROM_DATE",admin));}
    @Test void concurrentSettlementAndLateUploadNeverLoseWages()throws Exception{var op=create("5");ledger.apply(op,recorder);String month=date.toString().substring(0,7);try(var pool=Executors.newFixedThreadPool(2)){var latch=new CountDownLatch(1);var a=pool.submit(()->{latch.await();return payroll.settle(month,admin);});var b=pool.submit(()->{latch.await();return ledger.apply(new Operation(id(),"CREATE",id(),worker,date,"PM",product,input("2"),"晚传",null,""),other);});latch.countDown();a.get(20,TimeUnit.SECONDS);assertEquals("ACCEPTED",b.get(20,TimeUnit.SECONDS).status());}BigDecimal settled=db.jdbc.queryForObject("SELECT SUM(amount) FROM settlement_line",BigDecimal.class);if(settled.compareTo(new BigDecimal("21"))!=0)payroll.settle(month,admin);assertEquals(new BigDecimal("21.00"),db.jdbc.queryForObject("SELECT SUM(amount) FROM settlement_line",BigDecimal.class));}

    @Autowired Dispatch dispatch;@Autowired CatalogImport imports;@Autowired PrintSnapshots prints;
    String plot(String name){return str((Map<String,Object>)catalog.save("plots",Map.of("name",name),admin),"id");}
    Operation atPlot(String record,String action,String mode,String group,String plot,String amount,Integer version,String why){return new Operation(id(),action,record,worker,date,"AM",product,input(amount),"",version,why,new Entry(mode,group,input(amount),plot,null));}
    @Test void plotAllocationsPreserveUniqueRecordAndPaperSignatureOnPlotMove(){
        String g=group("一组"),a=plot("一号棚"),b=plot("二号棚"),r=id();
        ledger.apply(atPlot(r,"CREATE","ADD",g,a,"2",null,""),recorder);
        ledger.apply(atPlot(r,"UPDATE","ADD",g,b,"3",1,""),other);
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM harvest_record",Integer.class));
        var costs=ledger.workload.costs(r,catalog.rule(product,date),(Quote)ledger.enrich(ledger.record(r)).get("quote"));
        decimal("15.00",costs.stream().map(c->(BigDecimal)c.get("amount")).reduce(BigDecimal.ZERO,BigDecimal::add));
        daily.sign(new DailyReview.Signature(worker,date,str(dailyWorker(),"fingerprint")),other);
        var move=new Operation(id(),"UPDATE",r,worker,date,"AM",product,input("0"),"",2,"地块核对",new Entry("MOVE",g,input("0"),a,b));
        ledger.apply(move,other);assertEquals("SIGNED",dailyWorker().get("status"));
        assertEquals(1,ledger.workload.plots(r).size());decimal("5",db.json.read(str(ledger.record(r),"inputs_json"),Inputs.class).quantities().get("main"));
        payroll.settle(date.toString().substring(0,7),admin);
        String frozen=str(db.one("SELECT * FROM settlement_line WHERE record_id=?",r),"snapshot_json");
        ledger.apply(atPlot(r,"UPDATE","CORRECT",g,b,"6",3,"他人已结算记录核对"),other);
        assertEquals(frozen,str(db.one("SELECT * FROM settlement_line WHERE record_id=?",r),"snapshot_json"));
        decimal("3.00",wages(date.toString().substring(0,7),worker).get("pending"));assertEquals("RESIGN",dailyWorker().get("status"));
    }
    @Test void plotCostRemaindersNeverChangeWagesAndMixedStagesRemainSeparate(){
        var rule=new Rule("SEGMENTS",List.of(new Stage("main","细价计件","PIECE",new BigDecimal("0.0033"),null,null,null,null)));
        catalog.publish(product,date,rule,"尾差测试",admin);String g=group("一组"),r=id();
        for(int i=0;i<3;i++)ledger.apply(atPlot(r,i==0?"CREATE":"UPDATE","ADD",g,plot("棚"+i),"1",i==0?null:i,""),recorder);
        var costs=ledger.workload.costs(r,rule,(Quote)ledger.enrich(ledger.record(r)).get("quote"));
        decimal("0.01",costs.stream().map(c->(BigDecimal)c.get("amount")).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals(1,costs.stream().filter(c->((BigDecimal)c.get("amount")).signum()>0).count());
    }
    String excel(String kind,List<List<String>> values)throws Exception{
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(imports.template(kind)));var out=new java.io.ByteArrayOutputStream()){
            var sheet=book.getSheetAt(0);for(int i=sheet.getLastRowNum();i>0;i--)if(sheet.getRow(i)!=null)sheet.removeRow(sheet.getRow(i));
            int n=1;for(var row:values){var r=sheet.createRow(n++);for(int i=0;i<row.size();i++)r.createCell(i).setCellValue(row.get(i));}book.write(out);return Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }
    @Test void retiredDispatchRefusesNewWorkAndDoesNotGenerateWages(){
        assertEquals(410,assertThrows(Problem.class,()->dispatch.day(date)).status);
        assertEquals(410,assertThrows(Problem.class,()->dispatch.apply(null,recorder)).status);
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM harvest_record",Integer.class));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_plan",Integer.class));
    }

    @Test void recorderImportPreviewsWithoutWritesAndCommitsRelatedGroupsAtomically()throws Exception{
        var source=excel("workers",List.of(List.of("","NEW01","临时工","123","新三组","","","是")));
        var preview=(Map<String,Object>)imports.preview(new CatalogImport.Upload("workers",source),recorder);assertEquals(true,preview.get("valid"));
        assertFalse(db.exists("SELECT id FROM farm_group WHERE name='新三组'"));assertFalse(db.exists("SELECT id FROM worker WHERE code='NEW01'"));
        imports.commit(str(preview,"id"),recorder);imports.commit(str(preview,"id"),recorder);
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM worker WHERE code='NEW01'",Integer.class));assertTrue(db.exists("SELECT id FROM farm_group WHERE name='新三组'"));
        var skipped=(Map<String,Object>)imports.preview(new CatalogImport.Upload("workers",excel("workers",List.of(List.of("","NEW01","不能覆盖","","其他组","","","是")))),other);
        imports.commit(str(skipped,"id"),other);assertEquals("临时工",str(db.one("SELECT * FROM worker WHERE code='NEW01'"),"name"));assertFalse(db.exists("SELECT id FROM farm_group WHERE name='其他组'"));
    }
    @Test void importRejectsAnyInvalidRowAndStalePreviewRollsBackAllWrites()throws Exception{
        String g=group("原组");
        var preview=(Map<String,Object>)imports.preview(new CatalogImport.Upload("groups",excel("groups",List.of(List.of(g,"","更名","","","","","是"),List.of("","","新组","","","","","是")))),recorder);
        assertEquals(true,preview.get("valid"));catalog.save("groups",Map.of("id",g,"name","他人刚改"),admin);
        assertThrows(Problem.class,()->imports.commit(str(preview,"id"),recorder));assertFalse(db.exists("SELECT id FROM farm_group WHERE name='新组'"));assertEquals("他人刚改",str(db.one("SELECT * FROM farm_group WHERE id=?",g),"name"));
        var bad=(Map<String,Object>)imports.preview(new CatalogImport.Upload("workers",excel("workers",List.of(List.of("","N1","有效行","","暂建组","","","是"),List.of("","N2","x".repeat(81),"","暂建组","","","是")))),other);
        assertEquals(false,bad.get("valid"));assertFalse(db.exists("SELECT id FROM farm_group WHERE name='暂建组'"));assertThrows(Problem.class,()->imports.commit(str(bad,"id"),other));assertFalse(db.exists("SELECT id FROM worker WHERE code='N1'"));
    }
    @Test void printSnapshotsStayImmutableAndRecorderCanSignOtherWorkersPastDay(){
        String g=group("一组"),a=plot("一号棚"),r=id();LocalDate past=date.minusDays(1);
        ledger.apply(new Operation(id(),"CREATE",r,worker,past,"AM",product,input("2"),"补录",null,"",new Entry("ADD",g,input("2"),a,null)),recorder);
        var day=(Map<String,Object>)daily.day(past,other);var w=((List<Map<String,Object>>)day.get("workers")).getFirst();
        daily.sign(new DailyReview.Signature(worker,past,str(w,"fingerprint")),other);
        var capture=new PrintSnapshots.Capture(id(),past,"*",Map.of(worker,str(w,"fingerprint")));
        prints.capture(capture,other);String original=str(db.one("SELECT * FROM daily_print_snapshot WHERE id=?",capture.id()),"snapshot_json");
        ledger.apply(new Operation(id(),"UPDATE",r,worker,past,"AM",product,input("3"),"补录",1,"核实少记",new Entry("CORRECT",g,input("3"),a,null)),other);
        ledger.apply(new Operation(id(),"CREATE",id(),worker,past,"PM",product,input("1"),"打印后补录下午工作",null,""),recorder);
        var result=(Map<String,Object>)prints.compare(capture.id(),recorder);assertEquals(original,str(db.one("SELECT * FROM daily_print_snapshot WHERE id=?",capture.id()),"snapshot_json"));
        var snapshot=(Map<String,Object>)result.get("snapshot");var current=(Map<String,Object>)result.get("current");
        assertEquals("6.0",String.valueOf(((List<Map<String,Object>>)snapshot.get("workers")).getFirst().get("total")));
        decimal("12.00",((List<Map<String,Object>>)current.get("workers")).getFirst().get("total"));assertTrue(((List<?>)result.get("changes")).size()>=2);
        assertThrows(Problem.class,()->prints.capture(new PrintSnapshots.Capture(id(),past,"*",Map.of(worker,str(w,"fingerprint"))),other));
    }

    @Test void mixedAndAutomaticPlotCostAllocationsFollowConfirmedRules(){
        String g=group("一组"),a=plot("一号棚"),b=plot("二号棚"),r=id();
        var mixed=new Rule("SEGMENTS",List.of(new Stage("pieces","计件","PIECE",new BigDecimal("2"),null,null,null,null),new Stage("weight","计重","WEIGHT",new BigDecimal("0.2"),null,null,null,null)));
        catalog.publish(product,date,mixed,"分段混合测试",admin);
        Inputs first=new Inputs(Map.of("pieces",new BigDecimal("2"),"weight",new BigDecimal("10")),null,"kg"),second=new Inputs(Map.of("pieces",new BigDecimal("3"),"weight",new BigDecimal("20")),null,"kg");
        ledger.apply(new Operation(id(),"CREATE",r,worker,date,"AM",product,first,"",null,"",new Entry("ADD",g,first,a,null)),recorder);
        ledger.apply(new Operation(id(),"UPDATE",r,worker,date,"AM",product,second,"",1,"",new Entry("ADD",g,second,b,null)),other);
        var costs=ledger.workload.costs(r,mixed,(Quote)ledger.enrich(ledger.record(r)).get("quote"));
        decimal("6.00",costs.stream().filter(c->a.equals(c.get("plot_id"))).findFirst().orElseThrow().get("amount"));decimal("10.00",costs.stream().filter(c->b.equals(c.get("plot_id"))).findFirst().orElseThrow().get("amount"));
        String category=str(db.one("SELECT category_id FROM product WHERE id=?",product),"category_id");String automatic=str((Map<String,Object>)catalog.save("products",Map.of("name","自动分配测试","categoryId",category),admin),"id");
        var allocate=new Rule("ALLOCATE",List.of(new Stage("piece","先计件","PIECE",new BigDecimal("3"),new BigDecimal("10"),null,null,null),new Stage("weight","余量计重","WEIGHT",new BigDecimal("0.5"),null,null,null,null)));
        catalog.publish(automatic,date,allocate,"自动分配测试",admin);String record=id();
        int version=0;for(var amount:List.of("20","32")){Inputs in=new Inputs(Map.of(),new BigDecimal(amount),"kg");ledger.apply(new Operation(id(),version==0?"CREATE":"UPDATE",record,worker,date,"AM",automatic,in,"",version==0?null:version,"",new Entry("ADD",g,in,version==0?a:b,null)),recorder);version++;}
        var automaticCosts=ledger.workload.costs(record,allocate,(Quote)ledger.enrich(ledger.record(record)).get("quote"));
        decimal("6.15",automaticCosts.stream().filter(c->a.equals(c.get("plot_id"))).findFirst().orElseThrow().get("amount"));decimal("9.85",automaticCosts.stream().filter(c->b.equals(c.get("plot_id"))).findFirst().orElseThrow().get("amount"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"groups","categories","products","plots","baskets"})
    void everyNonWorkerImportSupportsPreviewCreateUpdateAndOwnerBinding(String kind)throws Exception{
        String name="回归资料-"+kind;
        var values=List.of("","",name,"","","自动关联类别","1.25","是");
        var preview=(Map<String,Object>)imports.preview(new CatalogImport.Upload(kind,excel(kind,List.of(values))),recorder);
        assertEquals(true,preview.get("valid"));String table=CatalogImport.TABLES.get(kind);
        assertFalse(db.exists("SELECT id FROM "+table+" WHERE name=?",name));
        assertThrows(Problem.class,()->imports.commit(str(preview,"id"),other));
        imports.commit(str(preview,"id"),recorder);
        var original=db.one("SELECT * FROM "+table+" WHERE name=?",name);
        var update=List.of(str(original,"id"),"",name+"更名","","","自动关联类别","2.5","否");
        var next=(Map<String,Object>)imports.preview(new CatalogImport.Upload(kind,excel(kind,List.of(update))),recorder);
        assertEquals(true,next.get("valid"));assertEquals(name,str(db.one("SELECT * FROM "+table+" WHERE id=?",original.get("id")),"name"));
        imports.commit(str(next,"id"),recorder);
        var changed=db.one("SELECT * FROM "+table+" WHERE id=?",original.get("id"));
        assertEquals(name+"更名",str(changed,"name"));assertFalse(flag(changed.get("active")));
        if(kind.equals("baskets"))decimal("2.5",changed.get("weight_kg"));
        if(kind.equals("products"))assertEquals("自动关联类别",str(db.one("SELECT name FROM category WHERE id=?",changed.get("category_id")),"name"));
    }

    @Test void paymentRetryBindsPayloadAndConcurrentPaymentsCannotOverpay()throws Exception{
        ledger.apply(create("5"),recorder);String month=date.toString().substring(0,7);payroll.settle(month,admin);
        String payment=id();payroll.pay(payment,worker,month,new BigDecimal("5"),"现金","回归付款",admin);
        payroll.pay(payment,worker,month,new BigDecimal("5.00"),"现金","回归付款",admin);
        assertThrows(Problem.class,()->payroll.pay(payment,worker,month,new BigDecimal("6"),"现金","回归付款",admin));
        try(var pool=Executors.newFixedThreadPool(2)){
            var latch=new CountDownLatch(1);Callable<Boolean> task=()->{latch.await();try{payroll.pay(id(),worker,month,new BigDecimal("7"),"现金","并发付款",admin);return true;}catch(Problem expected){return false;}};
            var a=pool.submit(task);var b=pool.submit(task);latch.countDown();
            assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        decimal("12",db.jdbc.queryForObject("SELECT SUM(amount) FROM payment",BigDecimal.class));
        assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='PAYMENT'",Integer.class));
    }

    @Test void reducingAlreadyPaidWorkPreservesSnapshotsAndShowsOverpayment(){
        var op=create("5");ledger.apply(op,recorder);String month=date.toString().substring(0,7);payroll.settle(month,admin);
        String snapshot=str(db.one("SELECT * FROM settlement_line"),"snapshot_json");
        payroll.pay(id(),worker,month,new BigDecimal("15"),"现金","已付清",admin);
        ledger.apply(new Operation(id(),"UPDATE",op.recordId(),worker,date,"AM",product,input("3"),"",1,"核对少做两筐"),other);
        assertEquals(snapshot,str(db.one("SELECT * FROM settlement_line"),"snapshot_json"));
        payroll.settle(month,admin);
        var w=((List<Map<String,Object>>)payroll.preview(month).get("workers")).getFirst();
        decimal("9",w.get("earned"));decimal("9",w.get("settled"));decimal("15",w.get("paid"));decimal("-6",w.get("unpaid"));decimal("0",w.get("pending"));
        assertThrows(Problem.class,()->payroll.settle(month,admin));
        assertThrows(Problem.class,()->payroll.pay(id(),worker,month,BigDecimal.ONE,"现金","不允许继续付款",admin));
    }

    @Test void importFormulaAndMalformedFilesNeverCreateBusinessData()throws Exception{
        int before=db.jdbc.queryForObject("SELECT COUNT(*) FROM worker",Integer.class);
        assertThrows(Problem.class,()->imports.preview(new CatalogImport.Upload("workers","not-base64"),recorder));
        String source=excel("workers",List.of(List.of("","FORMULA","公式行","","","","","是")));
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(source)));var out=new java.io.ByteArrayOutputStream()){
            book.getSheetAt(0).getRow(1).getCell(2).setCellFormula("1+1");book.write(out);
            assertThrows(Problem.class,()->imports.preview(new CatalogImport.Upload("workers",Base64.getEncoder().encodeToString(out.toByteArray())),recorder));
        }
        assertEquals(before,db.jdbc.queryForObject("SELECT COUNT(*) FROM worker",Integer.class));
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM import_batch",Integer.class));
    }

    @Test void locationOnlyCorrectionKeepsLegacyJinSignatureAndExactTotal(){
        String weightProduct=str(db.one("SELECT id FROM product WHERE name='小白菜'"),"id"),r=id(),destination=plot("核实地块");
        Inputs jin=new Inputs(Map.of("main",new BigDecimal("20")),null,"jin");
        ledger.apply(new Operation(id(),"CREATE",r,worker,date,"AM",weightProduct,jin,"",null,""),recorder);
        daily.sign(new DailyReview.Signature(worker,date,str(dailyWorker(),"fingerprint")),other);
        String original=str(ledger.record(r),"inputs_json");
        ledger.apply(new Operation(id(),"UPDATE",r,worker,date,"AM",weightProduct,jin,"",1,"补齐历史地块",new Entry("MOVE","",jin,"",destination)),other);
        assertEquals(original,str(ledger.record(r),"inputs_json"));assertEquals("SIGNED",dailyWorker().get("status"));
        var costs=ledger.workload.costs(r,catalog.rule(weightProduct,date),(Quote)ledger.enrich(ledger.record(r)).get("quote"));decimal("5.00",costs.getFirst().get("amount"));
    }

    @Test void payAutomaticallySnapshotsOnlyCurrentWorkerAndCorrectionIsImmediatelyPayable(){
        String month=date.toString().substring(0,7);var original=create("5");ledger.apply(original,recorder);
        String second=str((Map<String,Object>)catalog.save("workers",Map.of("code","PAY-OTHER","name","另一工人"),admin),"id");
        ledger.apply(new Operation(id(),"CREATE",id(),second,date,"AM",product,input("8"),"",null,""),recorder);
        decimal("15",wages(month,worker).get("confirmed"));decimal("15",wages(month,worker).get("unpaid"));
        String payment=id();payroll.pay(payment,worker,month,new BigDecimal("10"),"现金","首次付款",admin);
        String snapshot=str(db.one("SELECT snapshot_json FROM settlement_line WHERE worker_id=?",worker),"snapshot_json");
        assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM settlement_line WHERE worker_id=?",Integer.class,second));
        ledger.apply(new Operation(id(),"UPDATE",original.recordId(),worker,date,"AM",product,input("7"),"",1,"补记两筐"),other);
        decimal("21",wages(month,worker).get("confirmed"));decimal("11",wages(month,worker).get("unpaid"));
        payroll.pay(payment,worker,month,new BigDecimal("10"),"现金","首次付款",admin);
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM payment",Integer.class));
        assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM settlement_line",Integer.class));
        payroll.pay(id(),worker,month,new BigDecimal("11"),"现金","补发",admin);
        decimal("0",wages(month,worker).get("unpaid"));assertTrue(db.exists("SELECT id FROM settlement_line WHERE snapshot_json=?",snapshot));
        ledger.apply(new Operation(id(),"UPDATE",original.recordId(),worker,date,"AM",product,input("3"),"",2,"复核减量"),recorder);
        decimal("9",wages(month,worker).get("confirmed"));decimal("-12",wages(month,worker).get("unpaid"));
        assertThrows(Problem.class,()->payroll.pay(id(),worker,month,BigDecimal.ONE,"现金","",admin));
    }

    @Test void configuredWeightsUseImmutableRevisionsAndRejectTamperedValues()throws Exception{
        catalog.publish(product,date,new Rule("SEGMENTS",List.of(new Stage("main","采摘重量","WEIGHT",BigDecimal.ONE,null,null,null,null))),"计重测试",admin);
        var gross=(Map<String,Object>)catalog.save("grossWeights",Map.of("name","配置毛重","weightKg","26"),recorder);
        var basket=(Map<String,Object>)catalog.save("baskets",Map.of("name","配置筐重","weightKg","2"),recorder);
        Weighing capture=new Weighing(new BigDecimal("26"),null,str(basket,"id"),new BigDecimal("2"),3,str(gross,"id"),1,1);
        Inputs in=new Inputs(Map.of("main",new BigDecimal("999")),null,"kg",Map.of("main",capture));
        String record=id();ledger.apply(quantity(record,"CREATE","ADD","",in,null,""),recorder);
        decimal("20",db.json.read(str(ledger.record(record),"inputs_json"),Inputs.class).quantities().get("main"));
        catalog.save("grossWeights",Map.of("id",gross.get("id"),"name","配置毛重","weightKg","30"),other);
        catalog.save("baskets",Map.of("id",basket.get("id"),"name","配置筐重","weightKg","3"),other);
        ledger.apply(quantity(record,"UPDATE","ADD","",in,1,"旧配置离线晚传"),other);
        decimal("40",db.json.read(str(ledger.record(record),"inputs_json"),Inputs.class).quantities().get("main"));
        Inputs bad=new Inputs(Map.of(),null,"kg",Map.of("main",new Weighing(new BigDecimal("27"),null,str(basket,"id"),new BigDecimal("2"),3,str(gross,"id"),1,1)));
        assertThrows(Problem.class,()->ledger.apply(quantity(record,"UPDATE","ADD","",bad,2,""),other));
        Inputs negative=new Inputs(Map.of(),null,"kg",Map.of("main",new Weighing(new BigDecimal("26"),null,str(basket,"id"),new BigDecimal("2"),20,str(gross,"id"),1,1)));
        assertThrows(Problem.class,()->ledger.apply(quantity(record,"UPDATE","ADD","",negative,2,""),other));
        String token=(String)((Map<?,?>)auth.login("recorder","Farm@2026")).get("token");
        mvc.perform(post("/api/catalog/grossWeights").header("Authorization","Bearer "+token).contentType("application/json").content("{\"name\":\"记录员维护毛重\",\"weightKg\":\"12\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/catalog/users").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/query/catalog/users").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/query/catalog/grossWeights").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }

    @Test void movingOneCompositionCanChangeGroupAndPlotWithoutChangingOtherQuantity(){
        String ga=group("原一组"),gb=group("原二组"),gc=group("新三组"),a=plot("原A棚"),b=plot("原B棚"),c=plot("新C棚"),record=id();
        ledger.apply(new Operation(id(),"CREATE",record,worker,date,"AM",product,input("3"),"",null,"",new Entry("ADD",ga,input("3"),a,null)),recorder);
        ledger.apply(new Operation(id(),"UPDATE",record,worker,date,"AM",product,input("2"),"",1,"",new Entry("ADD",gb,input("2"),b,null)),recorder);
        String original=str(ledger.record(record),"inputs_json");
        ledger.apply(new Operation(id(),"UPDATE",record,worker,date,"AM",product,input("3"),"",2,"调整归属",new Entry("MOVE",ga,input("3"),a,c,gc)),other);
        assertEquals(original,str(ledger.record(record),"inputs_json"));
        assertFalse(db.exists("SELECT record_id FROM record_plot WHERE record_id=? AND group_id=?",record,ga));
        decimal("2",db.json.read(str(db.one("SELECT inputs_json FROM record_plot WHERE record_id=? AND group_id=? AND plot_id=?",record,gb,b),"inputs_json"),Inputs.class).quantities().get("main"));
        decimal("3",db.json.read(str(db.one("SELECT inputs_json FROM record_group WHERE record_id=? AND group_id=?",record,gc),"inputs_json"),Inputs.class).quantities().get("main"));
    }

    @Test void queriesPageFilterBoundariesPermissionsAndRemovedDispatch()throws Exception{
        String token=(String)((Map<?,?>)auth.login("admin","Farm@2026")).get("token");
        String recorderToken=(String)((Map<?,?>)auth.login("recorder","Farm@2026")).get("token");
        for(int i=0;i<25;i++){
            var w=(Map<String,Object>)catalog.save("workers",Map.of("code",String.format("PAGE-%02d",i),"name","分页工人"+i),admin);
            ledger.apply(new Operation(id(),"CREATE",id(),str(w,"id"),date,"AM",product,input("1"),"",null,""),recorder);
        }
        var first=mvc.perform(get("/api/query/catalog/workers").param("q","PAGE-").param("size","10").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<?,?> page=db.json.read(first,Map.class);assertEquals(25,((Number)page.get("total")).intValue());assertEquals(10,((List<?>)page.get("items")).size());
        var last=mvc.perform(get("/api/query/catalog/workers").param("q","PAGE-").param("size","10").param("page","3").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(5,((List<?>)db.json.read(last,Map.class).get("items")).size());
        var exported=mvc.perform(get("/api/payroll/export").param("month",date.toString().substring(0,7)).param("q","PAGE-").param("page","1").param("size","10").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exported))){assertEquals(26,book.getSheetAt(0).getPhysicalNumberOfRows());}
        for(String kind:List.of("payments","settlements","audit","prints"))mvc.perform(get("/api/query/"+kind).param("month",date.toString().substring(0,7)).param("date",date.toString()).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/query/daily").param("date",date.toString()).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/query/plot-costs").param("month",date.toString().substring(0,7)).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/query/catalog/workers").param("page","0").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/query/catalog/workers").param("size","101").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        for(String kind:List.of("records","payroll","daily","plot-costs"))mvc.perform(get("/api/query/"+kind).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        assertThrows(Problem.class,()->payroll.pay(id(),null,date.toString().substring(0,7),BigDecimal.ONE,"现金","",admin));
        mvc.perform(get("/api/query/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/query/payroll").param("month",date.toString().substring(0,7)).header("Authorization","Bearer "+recorderToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/dispatch").param("date",date.toString()).header("Authorization","Bearer "+token)).andExpect(status().isGone());
        mvc.perform(post("/api/dispatch/operate").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isGone());
    }

}
