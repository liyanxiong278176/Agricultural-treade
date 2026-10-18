package cn.farm;

import static cn.farm.Domain.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Pricing {
    static final BigDecimal ZERO=BigDecimal.ZERO, HALF=new BigDecimal("0.5");
    public void validate(Rule rule){
        if(rule==null)fail("请填写计费规则");
        if(rule.mode()==null||!Set.of("SEGMENTS","ALLOCATE").contains(rule.mode()))fail("请选择有效的计费模式");
        if(rule.stages()==null||rule.stages().isEmpty()||rule.stages().size()>6)fail("请配置 1 至 6 个计费阶段");
        Set<String> keys=new HashSet<>();
        for(Stage s:rule.stages()){
            if(s==null)fail("计费阶段不能为空");
            if(s.key()==null||!s.key().matches("[a-zA-Z0-9_-]{1,40}")||!keys.add(s.key())||s.label()==null||s.label().isBlank()||s.label().length()>60)fail("阶段名称或标识不正确");
            if(s.type()==null||!Set.of("PIECE","WEIGHT").contains(s.type()))fail("请选择计件或计重类型");
            if(s.rate()==null||s.rate().signum()<0||s.rate().scale()>4||s.rate().compareTo(new BigDecimal("100000"))>0)fail("单价应为 0 至 100000，最多四位小数");
            for(BigDecimal b:Arrays.asList(s.limit(),s.min(),s.max()))if(b!=null&&(b.signum()<0||b.compareTo(new BigDecimal("10000000"))>0))fail("阶段条件超出范围");
            if(s.min()!=null&&s.max()!=null&&s.min().compareTo(s.max())>0)fail("条件下限不能大于上限");
            if("ALLOCATE".equals(rule.mode())&&"PIECE".equals(s.type())&&(s.kgPerPiece()==null||s.kgPerPiece().signum()<=0||s.kgPerPiece().scale()>6))fail("自动分配的计件阶段必须配置每筐标准公斤数");
            if("PIECE".equals(s.type())&&s.limit()!=null&&s.limit().remainder(HALF).signum()!=0)fail("计件上限必须为半筐的整数倍");
        }
    }
    public BigDecimal kg(BigDecimal value,String unit){
        if(value==null||value.signum()<0||value.compareTo(new BigDecimal("10000000"))>0||value.scale()>6)fail("重量无效或超过六位小数");
        if(unit==null)fail("请选择重量单位");
        return switch(unit){case "kg"->value;case "jin"->value.divide(new BigDecimal("2"));case "g"->value.divide(new BigDecimal("1000"));default->throw new Problem(400,"不支持的重量单位");};
    }
    public Quote calculate(Rule rule,Inputs input){
        validate(rule); if(input==null)fail("请填写数量");
        Map<String,BigDecimal> quantities=input.quantities()==null?Map.of():input.quantities();
        Set<String> keys=new HashSet<>();rule.stages().forEach(s->keys.add(s.key()));
        if(!keys.containsAll(quantities.keySet()))fail("录入项与当前规则不一致，请刷新产品规则");
        BigDecimal remaining="ALLOCATE".equals(rule.mode())?kg(input.totalWeight(),input.weightUnit()):ZERO,total=ZERO;
        List<Part> parts=new ArrayList<>();
        for(Stage s:rule.stages()){
            BigDecimal qty;
            if("ALLOCATE".equals(rule.mode())){
                qty="PIECE".equals(s.type())?remaining.divide(s.kgPerPiece().multiply(HALF),0,RoundingMode.DOWN).multiply(HALF):remaining;
            }else{
                qty=quantities.getOrDefault(s.key(),ZERO);if(qty==null)qty=ZERO;
                if(qty.signum()<0||qty.scale()>6||qty.compareTo(new BigDecimal("10000000"))>0)fail("数量超出范围");
                if("WEIGHT".equals(s.type()))qty=kg(qty,input.weightUnit());
            }
            if("PIECE".equals(s.type())&&qty.remainder(HALF).signum()!=0)fail("筐数只能是 0.5 的正整数倍");
            if((s.min()!=null&&qty.compareTo(s.min())<0)||(s.max()!=null&&qty.compareTo(s.max())>0)){
                if("SEGMENTS".equals(rule.mode())&&qty.signum()>0)fail(s.label()+"不满足计费条件");
                continue;
            }
            if(s.limit()!=null){
                if("SEGMENTS".equals(rule.mode())&&qty.compareTo(s.limit())>0)fail(s.label()+"超过阶段上限");
                qty=qty.min(s.limit());
            }
            if("ALLOCATE".equals(rule.mode()))remaining=remaining.subtract("PIECE".equals(s.type())?qty.multiply(s.kgPerPiece()):qty);
            BigDecimal amount=qty.multiply(s.rate());total=total.add(amount);parts.add(new Part(s.label(),s.type(),qty,s.rate(),amount));
        }
        if(total.compareTo(new BigDecimal("1000000000"))>0)fail("单笔金额超出范围");
        if(parts.stream().noneMatch(p->p.quantity().signum()>0))fail("请至少填写一项有效工作量");
        if(remaining.signum()>0)fail("还有 "+remaining.stripTrailingZeros().toPlainString()+" 公斤未分配，请检查阶段条件或上限");
        return new Quote(total.setScale(2,RoundingMode.HALF_UP),parts,remaining);
    }
    private void fail(String message){throw new Problem(400,message);}
}
