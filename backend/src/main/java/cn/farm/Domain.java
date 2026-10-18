package cn.farm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonInclude;

public final class Domain {
    private Domain() {}
    public record Actor(String id,String username,String name,String role) { public boolean admin(){return role.equals("ADMIN");} }
    public record Stage(String key,String label,String type,BigDecimal rate,BigDecimal kgPerPiece,BigDecimal limit,BigDecimal min,BigDecimal max) {}
    public record Rule(String mode,List<Stage> stages) {}
    public record Weighing(BigDecimal gross,BigDecimal tare,String basketId,BigDecimal basketWeight,Integer count,
        @JsonInclude(JsonInclude.Include.NON_NULL) String grossId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer grossRevision,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer basketRevision) {
        public Weighing(BigDecimal gross,BigDecimal tare,String basketId,BigDecimal basketWeight,Integer count){this(gross,tare,basketId,basketWeight,count,null,null,null);}
    }
    public record Inputs(Map<String,BigDecimal> quantities,BigDecimal totalWeight,String weightUnit,@JsonInclude(JsonInclude.Include.NON_NULL) Map<String,Weighing> weighings) {
        public Inputs(Map<String,BigDecimal> quantities,BigDecimal totalWeight,String weightUnit){this(quantities,totalWeight,weightUnit,null);}
    }
    public record Entry(String mode,String groupId,Inputs inputs,@JsonInclude(JsonInclude.Include.NON_NULL) String plotId,@JsonInclude(JsonInclude.Include.NON_NULL) String targetPlotId,@JsonInclude(JsonInclude.Include.NON_NULL) String targetGroupId) {
        public Entry(String mode,String groupId,Inputs inputs){this(mode,groupId,inputs,null,null,null);}
        public Entry(String mode,String groupId,Inputs inputs,String plotId,String targetPlotId){this(mode,groupId,inputs,plotId,targetPlotId,null);}
    }
    public record Part(String label,String type,BigDecimal quantity,BigDecimal rate,BigDecimal amount) {}
    public record Quote(BigDecimal amount,List<Part> parts,BigDecimal remainderKg) {}
    public record Operation(String operationId,String action,String recordId,String workerId,LocalDate workDate,String period,String productId,Inputs inputs,String note,Integer version,String reason,@JsonInclude(JsonInclude.Include.NON_NULL) Entry entry) {
        public Operation(String operationId,String action,String recordId,String workerId,LocalDate workDate,String period,String productId,Inputs inputs,String note,Integer version,String reason){this(operationId,action,recordId,workerId,workDate,period,productId,inputs,note,version,reason,null);}
    }
    public record Result(String status,String message,Map<String,Object> record) {}
    public static class Problem extends RuntimeException {
        public final int status; public final Object details;
        public Problem(int status,String message){this(status,message,null);}
        public Problem(int status,String message,Object details){super(message);this.status=status;this.details=details;}
    }
}
