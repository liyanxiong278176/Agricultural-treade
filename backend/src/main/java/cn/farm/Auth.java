package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Service
public class Auth {
    final Store db; final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    public Auth(Store db){this.db=db;}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public Actor actor(HttpServletRequest request){
        String header=request.getHeader("Authorization");if(header==null||!header.startsWith("Bearer "))throw new Problem(401,"请先登录");
        var rows=db.rows("SELECT u.* FROM farm_user u JOIN auth_session s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.active=TRUE",hash(header.substring(7)));
        if(rows.isEmpty())throw new Problem(401,"登录已过期，请重新登录；本机待同步记录仍保留");
        var u=rows.getFirst();return new Actor(str(u,"id"),str(u,"username"),str(u,"display_name"),str(u,"role"));
    }
    public Actor admin(HttpServletRequest request){Actor a=actor(request);if(!a.admin())throw new Problem(403,"仅老板可执行此操作");return a;}
    public Map<String,Object> login(String username,String password){
        var rows=db.rows("SELECT * FROM farm_user WHERE username=? AND active=TRUE",username);
        if(rows.isEmpty()||password==null||!passwords.matches(password,str(rows.getFirst(),"password_hash")))throw new Problem(401,"账号或密码不正确");
        var u=rows.getFirst();String token=id()+id();db.jdbc.update("INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,?)",hash(token),u.get("id"),LocalDateTime.now().plusHours(12));
        return Map.of("token",token,"user",new Actor(str(u,"id"),str(u,"username"),str(u,"display_name"),str(u,"role")));
    }
}
