package cn.farm;

import static cn.farm.Domain.*;
import static cn.farm.Store.*;
import java.util.*;
import java.io.*;
import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class CatalogImport {
    final Store db;final Catalog catalog;
    public CatalogImport(Store db,Catalog catalog){this.db=db;this.catalog=catalog;}
    static final Map<String,String> TABLES=Map.of("workers","worker","groups","farm_group","categories","category","products","product","plots","farm_plot","baskets","basket_type");
    static final List<String> HEAD=List.of("资料编号（请保留）","工号","名称","电话","班组","产品类别","单筐皮重（公斤）","启用（是/否）");
    String table(String kind){if(!TABLES.containsKey(kind))throw new Problem(400,"不支持的导入类型");return TABLES.get(kind);}
    public byte[] template(String kind)throws IOException{
        String table=table(kind);
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("资料");var h=sheet.createRow(0);for(int i=0;i<HEAD.size();i++){h.createCell(i).setCellValue(HEAD.get(i));sheet.setColumnWidth(i,i==0?11500:6000);}sheet.createFreezePane(0,1);
            int n=1;for(var r:db.rows("SELECT * FROM "+table+" ORDER BY id")){
                String group=kind.equals("workers")&&!str(r,"group_id").isBlank()?str(db.one("SELECT name FROM farm_group WHERE id=?",r.get("group_id")),"name"):"";
                String category=kind.equals("products")?str(db.one("SELECT name FROM category WHERE id=?",r.get("category_id")),"name"):"";
                String[] values={str(r,"id"),str(r,"code"),str(r,"name"),str(r,"phone"),group,category,str(r,"weight_kg"),flag(r.get("active"))?"是":"否"};
                var row=sheet.createRow(n++);for(int i=0;i<values.length;i++)row.createCell(i).setCellValue(values[i]);
            }
            book.write(out);return out.toByteArray();
        }
    }
    public record Upload(String kind,String file) {}
    public Object preview(Upload upload,Actor actor){
        if(upload==null||upload.file()==null||upload.file().length()>4_000_000)throw new Problem(400,"请选择不超过 3MB 的 Excel 文件");
        table(upload.kind());var source=new ArrayList<Map<String,String>>();
        try{
            byte[] data=Base64.getDecoder().decode(upload.file());
            try(var book=WorkbookFactory.create(new ByteArrayInputStream(data))){
                var sheet=book.getSheetAt(0);if(sheet.getLastRowNum()>1000)throw new Problem(400,"每批最多1000行资料");
                var format=new DataFormatter(Locale.ROOT);
                if(sheet.getRow(0)==null)throw new Problem(400,"请使用系统模板");
                for(int c=0;c<HEAD.size();c++)if(!HEAD.get(c).equals(format.formatCellValue(sheet.getRow(0).getCell(c)).trim()))throw new Problem(400,"表头不符，请下载系统模板");
                for(int i=1;i<=sheet.getLastRowNum();i++){
                    var row=sheet.getRow(i);if(row==null)continue;var r=new LinkedHashMap<String,String>();boolean filled=false;
                    for(int c=0;c<HEAD.size();c++){var cell=row.getCell(c);if(cell!=null&&cell.getCellType()==CellType.FORMULA)throw new Problem(400,"第"+(i+1)+"行含公式，请改为实际值");String value=format.formatCellValue(cell).trim();if(!value.isBlank())filled=true;r.put(HEAD.get(c),value);}
                    if(filled){r.put("row",Integer.toString(i+1));source.add(r);}
                }
            }
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem(400,"无法读取文件，请使用系统提供的 Excel 模板");}
        if(source.isEmpty())throw new Problem(400,"表格没有资料行");
        String batch=id();
        var rows=db.tx.execute(tx->{try{return execute(upload.kind(),source,batch,actor);}finally{tx.setRollbackOnly();}});
        String hash=Auth.hash(db.json.write(rows));
        db.jdbc.update("INSERT INTO import_batch(id,actor_id,kind,source_json,preview_hash) VALUES(?,?,?,?,?)",batch,actor.id(),upload.kind(),db.json.write(source),hash);
        return Map.of("id",batch,"rows",rows,"valid",rows.stream().noneMatch(r->r.containsKey("error")));
    }
    @SuppressWarnings("unchecked")
    public Object commit(String batch,Actor actor){
        return db.tx.execute(tx->{
            var saved=db.one("SELECT * FROM import_batch WHERE id=? FOR UPDATE",batch);
            if(!actor.id().equals(str(saved,"actor_id")))throw new Problem(403,"只能确认自己预览的导入");
            if(saved.get("result_json")!=null)return db.json.read(str(saved,"result_json"),Map.class);
            var source=(List<Map<String,String>>)(List<?>)db.json.read(str(saved,"source_json"),List.class);
            // Lock relevant catalogues in a stable order; revalidate against the preview before accepting writes.
            for(String t:List.of("category","farm_group","farm_plot","basket_type","product","worker"))db.rows("SELECT id FROM "+t+" ORDER BY id FOR UPDATE");
            var rows=execute(str(saved,"kind"),source,batch,actor);
            if(rows.stream().anyMatch(r->r.containsKey("error")))throw new Problem(409,"资料校验失败，整批未导入，请重新预览",rows);
            if(!Auth.hash(db.json.write(rows)).equals(str(saved,"preview_hash")))throw new Problem(409,"资料已变化，整批未导入，请重新预览确认");
            var result=Map.of("id",batch,"rows",rows,"message","整批导入成功");db.jdbc.update("UPDATE import_batch SET result_json=? WHERE id=?",db.json.write(result),batch);db.audit(actor,"IMPORT",batch,null,Map.of("kind",saved.get("kind"),"rows",rows.size()),"确认导入 "+saved.get("kind"));return result;
        });
    }
    List<Map<String,Object>> execute(String kind,List<Map<String,String>> source,String batch,Actor actor){
        String table=table(kind);var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(var row:source){
            var view=new LinkedHashMap<String,Object>();view.put("row",row.get("row"));view.put("name",row.get(HEAD.get(2)));
            try{
                String supplied=row.get(HEAD.get(0)),code=row.get(HEAD.get(1)),name=row.get(HEAD.get(2));
                String identity=kind.equals("workers")?code:supplied.isBlank()?name:supplied;
                if(identity.isBlank()||!seen.add(identity))throw new Problem(400,"缺少识别信息或文件内重复资料");
                if(kind.equals("workers")&&db.exists("SELECT id FROM worker WHERE code=?",code)){view.put("action","SKIP");view.put("message","工号已存在，跳过且不更新");result.add(view);continue;}
                Map<String,Object> before=null;
                if(!supplied.isBlank()){
                    before=db.one("SELECT * FROM "+table+" WHERE id=?",supplied);
                    if(kind.equals("workers"))throw new Problem(400,"已有工人编号不可换工号后当新人导入，请新增空编号行");
                }else if(!kind.equals("workers")&&db.exists("SELECT id FROM "+table+" WHERE name=?",name))throw new Problem(400,"名称已存在，请下载已有资料表保留编号后修改");
                String entity=before==null?UUID.nameUUIDFromBytes((batch+":"+row.get("row")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString():supplied;
                var body=new LinkedHashMap<String,Object>();body.put("id",entity);body.put("name",name);
                String active=row.get(HEAD.get(7));if(!active.isBlank()&&!Set.of("是","否").contains(active))throw new Problem(400,"启用请填写是或否");body.put("active",active.isBlank()?before==null||flag(before.get("active")):active.equals("是"));
                var related=new ArrayList<String>();
                if(kind.equals("workers")){body.put("code",code);body.put("phone",row.get(HEAD.get(3)));body.put("groupId",relation("groups",row.get(HEAD.get(4)),batch,actor,related));}
                if(kind.equals("products")){String category=row.get(HEAD.get(5));if(category.isBlank())throw new Problem(400,"产品类别必填");body.put("categoryId",relation("categories",category,batch,actor,related));}
                if(kind.equals("baskets"))body.put("weightKg",row.get(HEAD.get(6)));
                view.put("action",before==null?"CREATE":"UPDATE");view.put("before",before);view.put("related",related);
                view.put("after",catalog.save(kind,body,actor));
            }catch(Exception e){view.put("error",e instanceof Problem?e.getMessage():"资料重复或字段无效，请核对");}
            result.add(view);
        }
        return result;
    }
    String relation(String kind,String name,String batch,Actor actor,List<String> related){
        if(name.isBlank())return "";
        var matches=db.rows("SELECT * FROM "+table(kind)+" WHERE name=?",name);
        if(matches.size()>1)throw new Problem(400,"关联资料名称不唯一，请先核对资料");
        if(!matches.isEmpty()){if(!flag(matches.getFirst().get("active")))throw new Problem(400,"关联资料已停用："+name);return str(matches.getFirst(),"id");}
        String entity=UUID.nameUUIDFromBytes((batch+":"+kind+":"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        catalog.save(kind,Map.of("id",entity,"name",name,"active",true),actor);related.add("新建"+(kind.equals("groups")?"班组":"类别")+"："+name);return entity;
    }
}
