package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.time.*;
import java.util.*;
import java.io.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
@RequestMapping("/api")
public class ExpansionApi {
    final Auth auth;final CatalogImport imports;final PrintSnapshots prints;final Ledger ledger;
    public ExpansionApi(Auth auth,CatalogImport imports,PrintSnapshots prints,Ledger ledger){this.auth=auth;this.imports=imports;this.prints=prints;this.ledger=ledger;}
    @GetMapping("/dispatch") public Object plans(@RequestParam LocalDate date,HttpServletRequest req){auth.actor(req);throw new Problem(410,"每日派工功能已移除");}
    @PostMapping("/dispatch/operate") public Object plan(@RequestBody Dispatch.Change change,HttpServletRequest req){auth.actor(req);throw new Problem(410,"每日派工功能已移除");}
    @GetMapping("/imports/{kind}/template") public ResponseEntity<byte[]> template(@PathVariable String kind,HttpServletRequest req)throws IOException{auth.actor(req);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+kind+".xlsx").body(imports.template(kind));}
    @PostMapping("/imports/preview") public Object preview(@RequestBody CatalogImport.Upload upload,HttpServletRequest req){return imports.preview(upload,auth.actor(req));}
    @PostMapping("/imports/{id}/confirm") public Object commit(@PathVariable String id,HttpServletRequest req){return imports.commit(id,auth.actor(req));}
    @GetMapping("/daily/prints") public Object prints(@RequestParam LocalDate date,HttpServletRequest req){auth.actor(req);return prints.list(date);}
    @PostMapping("/daily/prints") public Object print(@RequestBody PrintSnapshots.Capture capture,HttpServletRequest req){return prints.capture(capture,auth.actor(req));}
    @GetMapping("/daily/prints/{id}") public Object compare(@PathVariable String id,HttpServletRequest req){return prints.compare(id,auth.actor(req));}
    @GetMapping("/plot-costs") public Object costs(@RequestParam String month,HttpServletRequest req){auth.actor(req);return ledger.list(auth.actor(req),month).stream().filter(r->!flag(r.get("deleted"))).toList();}
}
