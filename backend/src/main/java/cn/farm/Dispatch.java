package cn.farm;
import static cn.farm.Domain.*;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
/** Retired protocol: retain historical tables, refuse all new dispatch work. */
@Service
public class Dispatch {
    public record Change(String operationId,String id,Integer version,LocalDate date,String period,String workerId,String groupId,String productId,String plotId,String resolution,String reason,Map<String,String> destination) {}
    public Object day(LocalDate date){throw new Problem(410,"每日派工功能已移除");}
    public Object apply(Change change,Actor actor){throw new Problem(410,"每日派工功能已移除");}
}
