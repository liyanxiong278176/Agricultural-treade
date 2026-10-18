package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Group allocations belong to the unique period record, never to separate deliveries. */
@Service
public class Workload {
    final Store db; final Pricing pricing;
    public Workload(Store db,Pricing pricing){this.db=db;this.pricing=pricing;}
    public List<Map<String,Object>> groups(String record){return db.rows("SELECT * FROM record_group WHERE record_id=? ORDER BY group_id",record);}
    BigDecimal number(BigDecimal n){
        if(n==null||n.signum()<0||n.scale()>6||n.compareTo(new BigDecimal("10000000"))>0)throw new Problem(400,"数量应为非负数，最多六位小数且不超过一千万");
        return n;
    }
    public Inputs normalize(Inputs in,Rule rule){
        if(in==null)throw new Problem(400,"请填写数量");
        Map<String,BigDecimal> q=new TreeMap<>(in.quantities()==null?Map.of():in.quantities());
        BigDecimal weight=in.totalWeight();
        var allowed=new HashMap<String,String>();rule.stages().forEach(s->allowed.put(s.key(),s.type()));
        if(!allowed.keySet().containsAll(q.keySet()))throw new Problem(400,"录入项与当前规则不一致");
        if(in.weighings()!=null)for(var item:in.weighings().entrySet()){
            String key=item.getKey();Weighing w=item.getValue();
            if(w==null||!(rule.mode().equals("ALLOCATE")&&key.equals("totalWeight"))&&!(rule.mode().equals("SEGMENTS")&&"WEIGHT".equals(allowed.get(key))))throw new Problem(400,"扣皮只能用于对应的重量输入项");
            if(w.grossId()!=null){
                verifyPreset("GROSS",w.grossId(),w.grossRevision(),w.gross(),in.weightUnit());
                verifyPreset("BASKET",w.basketId(),w.basketRevision(),w.basketWeight(),in.weightUnit());
            }
            BigDecimal tare;
            if(w.basketId()!=null&&!w.basketId().isBlank()){
                if(!db.exists("SELECT id FROM basket_type WHERE id=?",w.basketId())||w.count()==null||w.count()<1||w.count()>100000)throw new Problem(400,"请选择有效筐型和实际筐数");
                // A cached unit tare is a snapshot in the selected input unit; later catalogue changes must not rewrite it.
                tare=number(w.basketWeight()).multiply(BigDecimal.valueOf(w.count()));
            }else tare=number(w.tare());
            BigDecimal gross=number(w.gross());number(tare);
            if(gross.compareTo(tare)<0)throw new Problem(400,"毛重不能小于皮重");
            BigDecimal net=gross.subtract(tare);
            if(key.equals("totalWeight"))weight=net;else q.put(key,net);
        }
        for(var e:q.entrySet()){
            BigDecimal n=number(e.getValue());
            if("WEIGHT".equals(allowed.get(e.getKey())))e.setValue(pricing.kg(n,in.weightUnit()));
            else if(n.remainder(new BigDecimal("0.5")).signum()!=0)throw new Problem(400,"计件数量必须是半筐的整数倍");
        }
        if(rule.mode().equals("ALLOCATE")){weight=pricing.kg(number(weight),in.weightUnit());q.clear();}
        else weight=null;
        return new Inputs(q,weight,"kg");
    }
    public List<Map<String,Object>> plots(String record){return db.rows("SELECT * FROM record_plot WHERE record_id=? ORDER BY group_id,plot_id",record);}
    void seed(String record,Map<String,Object> old,Rule rule){
        if(old==null)return;
        if(!plots(record).isEmpty()){
            for(var part:plots(record)){Inputs original=db.json.read(str(part,"inputs_json"),Inputs.class);if(!"kg".equals(original.weightUnit()))db.jdbc.update("UPDATE record_plot SET inputs_json=? WHERE record_id=? AND group_id=? AND plot_id=?",db.json.write(normalize(original,rule)),record,str(part,"group_id"),str(part,"plot_id"));}
            return;
        }
        var groups=groups(record);
        if(groups.isEmpty())db.jdbc.update("INSERT INTO record_plot VALUES(?,?,?,?,?,?)",record,"","","未分组","未指定地块",db.json.write(normalize(db.json.read(str(old,"inputs_json"),Inputs.class),rule)));
        else for(var g:groups)db.jdbc.update("INSERT INTO record_plot VALUES(?,?,?,?,?,?)",record,str(g,"group_id"),"",str(g,"group_name"),"未指定地块",str(g,"inputs_json"));
    }
    public Inputs apply(Operation op,Map<String,Object> old,Rule rule){
        Entry entry=op.entry();if(entry==null)throw new Problem(400,"缺少数量操作");
        if(!Set.of("ADD","CORRECT","MOVE").contains(Objects.toString(entry.mode(),"")))throw new Problem(400,"请选择增加、更正数量或更正地块");
        if(!entry.mode().equals("ADD")&&(op.reason()==null||op.reason().isBlank()))throw new Problem(400,"更正必须填写原因");
        seed(op.recordId(),old,rule);
        String group=Objects.toString(entry.groupId(),""),plot=Objects.toString(entry.plotId(),"");
        var previous=plots(op.recordId()).stream().filter(r->group.equals(str(r,"group_id"))&&plot.equals(str(r,"plot_id"))).findFirst();
        String groupName=group.isBlank()?"未分组":str(db.one("SELECT * FROM farm_group WHERE id=?",group),"name");
        String plotName=plot.isBlank()?"未指定地块":str(db.one("SELECT * FROM farm_plot WHERE id=?",plot),"name");
        if(entry.mode().equals("ADD")){
            if(!group.isBlank()&&!db.exists("SELECT id FROM farm_group WHERE id=? AND active=TRUE",group))throw new Problem(400,"班组已停用");
            if(!plot.isBlank()&&!db.exists("SELECT id FROM farm_plot WHERE id=? AND active=TRUE",plot))throw new Problem(400,"地块已停用");
        }
        if(old!=null&&!entry.mode().equals("ADD")&&previous.isEmpty())throw new Problem(400,"该班组地块没有已登记数量");
        if(entry.mode().equals("MOVE")){
            String destination=entry.targetPlotId(),destinationGroup=Objects.toString(entry.targetGroupId(),group);
            if(old==null||destination==null||(plot.equals(destination)&&group.equals(destinationGroup)))throw new Problem(400,"请选择不同的班组或地块");
            String destName="未指定地块",destGroupName="未分组";
            if(!destination.isBlank())destName=str(db.one("SELECT * FROM farm_plot WHERE id=? AND active=TRUE",destination),"name");
            if(!destinationGroup.isBlank())destGroupName=str(db.one("SELECT * FROM farm_group WHERE id=? AND active=TRUE",destinationGroup),"name");
            Inputs amount=db.json.read(str(previous.orElseThrow(),"inputs_json"),Inputs.class);
            var dest=plots(op.recordId()).stream().filter(r->destinationGroup.equals(str(r,"group_id"))&&destination.equals(str(r,"plot_id"))).findFirst();
            if(dest.isPresent())amount=plus(amount,db.json.read(str(dest.get(),"inputs_json"),Inputs.class));
            db.jdbc.update("DELETE FROM record_plot WHERE record_id=? AND group_id=? AND plot_id=?",op.recordId(),group,plot);
            if(dest.isPresent())db.jdbc.update("UPDATE record_plot SET inputs_json=? WHERE record_id=? AND group_id=? AND plot_id=?",db.json.write(amount),op.recordId(),destinationGroup,destination);
            else db.jdbc.update("INSERT INTO record_plot VALUES(?,?,?,?,?,?)",op.recordId(),destinationGroup,destination,destGroupName,destName,db.json.write(amount));
            if(group.equals(destinationGroup))return db.json.read(str(old,"inputs_json"),Inputs.class);
        }else{
            Inputs incoming=normalize(entry.inputs(),rule);
            if(entry.mode().equals("ADD")&&!positive(incoming))throw new Problem(400,"请填写本次增加量");
            Inputs next=entry.mode().equals("ADD")&&previous.isPresent()?plus(db.json.read(str(previous.get(),"inputs_json"),Inputs.class),incoming):incoming;
            if(previous.isPresent())db.jdbc.update("UPDATE record_plot SET inputs_json=? WHERE record_id=? AND group_id=? AND plot_id=?",db.json.write(next),op.recordId(),group,plot);
            else db.jdbc.update("INSERT INTO record_plot VALUES(?,?,?,?,?,?)",op.recordId(),group,plot,groupName,plotName,db.json.write(next));
        }
        // Preserve the existing group-only view and signing fingerprint: a plot move does not change group totals.
        db.jdbc.update("DELETE FROM record_group WHERE record_id=?",op.recordId());
        var totals=new TreeMap<String,Inputs>();var names=new HashMap<String,String>();
        Inputs total=new Inputs(new TreeMap<>(),rule.mode().equals("ALLOCATE")?BigDecimal.ZERO:null,"kg");
        for(var row:plots(op.recordId())){
            Inputs in=db.json.read(str(row,"inputs_json"),Inputs.class);total=plus(total,in);
            String g=str(row,"group_id");totals.merge(g,in,this::plus);names.put(g,str(row,"group_name"));
        }
        for(var item:totals.entrySet())db.jdbc.update("INSERT INTO record_group VALUES(?,?,?,?)",op.recordId(),item.getKey(),names.get(item.getKey()),db.json.write(item.getValue()));
        return entry.mode().equals("MOVE")?db.json.read(str(old,"inputs_json"),Inputs.class):total;
    }
    public List<Map<String,Object>> costs(String record,Rule rule,Quote quote){
        var allocations=plots(record);if(allocations.isEmpty())return List.of();
        var raw=new ArrayList<BigDecimal>();BigDecimal sum=BigDecimal.ZERO;
        for(var row:allocations){
            Inputs in=normalize(db.json.read(str(row,"inputs_json"),Inputs.class),rule);BigDecimal cost=BigDecimal.ZERO;
            if(rule.mode().equals("ALLOCATE"))cost=in.totalWeight();
            else for(Stage s:rule.stages())cost=cost.add(in.quantities().getOrDefault(s.key(),BigDecimal.ZERO).multiply(s.rate()));
            raw.add(cost);sum=sum.add(cost);
        }
        BigDecimal cents=quote.amount().movePointRight(2);var amounts=new ArrayList<BigDecimal>();var remainders=new ArrayList<BigDecimal>();long assigned=0;
        for(var value:raw){BigDecimal exact=sum.signum()==0?BigDecimal.ZERO:cents.multiply(value).divide(sum,24,RoundingMode.DOWN);BigDecimal whole=exact.setScale(0,RoundingMode.DOWN);amounts.add(whole);remainders.add(exact.subtract(whole));assigned+=whole.longValueExact();}
        var order=new ArrayList<Integer>();for(int i=0;i<allocations.size();i++)order.add(i);
        order.sort((a,b)->remainders.get(b).compareTo(remainders.get(a)));
        long left=cents.longValueExact()-assigned;for(int i=0;i<left;i++){int index=order.get(i%order.size());amounts.set(index,amounts.get(index).add(BigDecimal.ONE));}
        var result=new ArrayList<Map<String,Object>>();for(int i=0;i<allocations.size();i++){var row=new LinkedHashMap<>(allocations.get(i));row.put("inputs_json",db.json.write(normalize(db.json.read(str(row,"inputs_json"),Inputs.class),rule)));row.put("amount",amounts.get(i).movePointLeft(2));result.add(row);}return result;
    }
    void verifyPreset(String kind,String preset,Integer revision,BigDecimal value,String unit){
        if(preset==null||revision==null)throw new Problem(400,"请选择毛重和筐重配置");
        var rows=db.rows("SELECT weight_kg FROM weight_preset_revision WHERE kind=? AND preset_id=? AND revision=?",kind,preset,revision);
        if(rows.isEmpty()||value==null||pricing.kg(value,unit).compareTo((BigDecimal)rows.getFirst().get("weight_kg"))!=0)throw new Problem(400,"重量与所选配置版本不一致，请重新选择配置");
    }
    boolean positive(Inputs in){return in.totalWeight()!=null&&in.totalWeight().signum()>0||in.quantities().values().stream().anyMatch(n->n.signum()>0);}
    Inputs plus(Inputs a,Inputs b){var q=new TreeMap<>(a.quantities());b.quantities().forEach((k,v)->q.merge(k,v,BigDecimal::add));return new Inputs(q,a.totalWeight()==null?b.totalWeight():a.totalWeight().add(b.totalWeight()==null?BigDecimal.ZERO:b.totalWeight()),"kg");}
}
