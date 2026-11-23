package com.xinhe.nexus.management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinhe.nexus.management.auth.ManagementUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceRuleService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public AttendanceRuleService(JdbcTemplate db) { this(db, Clock.system(ZoneId.of("Asia/Shanghai"))); }
    AttendanceRuleService(JdbcTemplate db, Clock clock) { this.db = db; this.json = new ObjectMapper(); this.clock = clock; }

    public Map<String,Object> getYear(ManagementUser user, int year) {
        requireAdmin(user); validateYear(year);
        List<Map<String,Object>> heads=db.queryForList("SELECT id,status,company_configured companyConfigured,draft_revision draftRevision,published_revision publishedRevision,version,published_at publishedAt FROM oa_attendance_rule_year WHERE company_id=? AND rule_year=?",user.companyId(),year);
        if(heads.isEmpty()) return Map.of("year",year,"status","UNCONFIGURED","companyConfigured",false,"draftRevision",1,"version",0,"companyHolidayDates",List.of(),"conflicts",List.of(),"requiredPersonalHolidayCount",requiredPersonalHolidayCount(year));
        Map<String,Object> out=new LinkedHashMap<>(heads.get(0)); long id=num(out.get("id")); int rev=num(out.get("draftRevision"));
        out.put("year",year); out.put("requiredPersonalHolidayCount",requiredPersonalHolidayCount(year)); out.put("companyHolidayDates",dates("SELECT DATE_FORMAT(holiday_date,'%Y-%m-%d') d FROM oa_attendance_company_holiday WHERE rule_year_id=? AND revision=? ORDER BY holiday_date",id,rev)); out.put("conflicts",conflicts(id,rev)); return out;
    }

    public List<Map<String,Object>> users(ManagementUser user,int year){
        requireAdmin(user); validateYear(year);
        Map<String,Object> annual=getYear(user,year); Object ruleId=annual.get("id"); int revision=num(annual.get("draftRevision"));
        List<Map<String,Object>> rows=db.queryForList("""
            SELECT u.id,u.display_name name,u.username,
              COALESCE((SELECT COUNT(*) FROM oa_attendance_user_holiday h JOIN oa_attendance_rule_year r ON r.id=h.rule_year_id WHERE r.company_id=uc.company_id AND r.rule_year=? AND h.revision=r.draft_revision AND h.user_id=u.id AND h.holiday_date>?),0) holidayCount,
              COALESCE((SELECT COUNT(*) FROM oa_attendance_user_holiday h JOIN oa_attendance_rule_year r ON r.id=h.rule_year_id JOIN oa_attendance_company_holiday c ON c.rule_year_id=r.id AND c.revision=r.draft_revision AND c.holiday_date=h.holiday_date WHERE r.company_id=uc.company_id AND r.rule_year=? AND h.revision=r.draft_revision AND h.user_id=u.id AND h.holiday_date>?),0) conflictCount
            FROM sys_user_company uc JOIN sys_user u ON u.id=uc.user_id AND u.status='ENABLED' AND u.deleted_at IS NULL
            WHERE uc.company_id=? AND uc.status='ENABLED' AND uc.deleted_at IS NULL ORDER BY u.display_name,u.id
            """,year,LocalDate.now(clock),year,LocalDate.now(clock),user.companyId());
        for(Map<String,Object> row:rows){long userId=((Number)row.get("id")).longValue();row.put("requiredPersonalHolidayCount",ruleId==null?requiredPersonalHolidayCount(year):requiredPersonalHolidayCount(year,((Number)ruleId).longValue(),revision,userId));}
        return rows;
    }

    public Map<String,Object> getUser(ManagementUser user,int year,long userId){
        requireAdmin(user); validateYear(year); requireCompanyUser(user,userId);
        Map<String,Object> annual=getYear(user,year); Object id=annual.get("id");
        List<String> personal=id==null?List.of():dates("SELECT DATE_FORMAT(holiday_date,'%Y-%m-%d') d FROM oa_attendance_user_holiday WHERE rule_year_id=? AND revision=? AND user_id=? ORDER BY holiday_date",id,num(annual.get("draftRevision")),userId);
        Set<String> company=new LinkedHashSet<>((List<String>)annual.get("companyHolidayDates")); List<String> collisions=personal.stream().filter(company::contains).toList();
        int required=id==null?requiredPersonalHolidayCount(year):requiredPersonalHolidayCount(year,((Number)id).longValue(),num(annual.get("draftRevision")),userId);
        return Map.of("year",year,"userId",userId,"companyHolidayDates",company,"personalHolidayDates",personal,"conflictDates",collisions,"companyConfigured",annual.get("companyConfigured"),"version",annual.get("version"),"requiredPersonalHolidayCount",required);
    }

    @Transactional
    public Map<String,Object> saveCompany(ManagementUser user,int year,DatesInput input){
        requireAdmin(user); validateYear(year); Set<LocalDate> dates=validateDates(year,input); Map<String,Object> head=lockOrCreate(user,year); ensureVersion(head,input.version()); head=ensureDraft(user,head);
        long id=num(head.get("id")); int rev=num(head.get("draftRevision")); assertFrozenDatesUnchanged(dates, dateValues("SELECT holiday_date FROM oa_attendance_company_holiday WHERE rule_year_id=? AND revision=?",id,rev)); db.update("DELETE FROM oa_attendance_company_holiday WHERE rule_year_id=? AND revision=?",id,rev);
        dates.forEach(d->db.update("INSERT INTO oa_attendance_company_holiday(rule_year_id,company_id,revision,holiday_date,created_by) VALUES(?,?,?,?,?)",id,user.companyId(),rev,d,user.id()));
        db.update("UPDATE oa_attendance_rule_year SET company_configured=1,status='DRAFT',version=version+1,updated_by=? WHERE id=?",user.id(),id); log(user,id,rev,"SAVE_COMPANY",null,dates.stream().map(LocalDate::toString).toList());
        Map<String,Object> result=getYear(user,year); result.put("saved",true); return result;
    }

    @Transactional
    public Map<String,Object> saveUser(ManagementUser user,int year,long userId,DatesInput input){
        requireAdmin(user); validateYear(year); requireCompanyUser(user,userId); Set<LocalDate> dates=validateDates(year,input); Map<String,Object> head=lockExisting(user,year); ensureVersion(head,input.version()); head=ensureDraft(user,head); long id=num(head.get("id")); int rev=num(head.get("draftRevision")); int required=requiredPersonalHolidayCount(year,id,rev,userId); long editableCount=dates.stream().filter(d->d.isAfter(LocalDate.now(clock))).count(); if(editableCount!=required)throw bad("剩余个人放假必须设置"+required+"天，当前已设置"+editableCount+"天");
        if(num(head.get("companyConfigured"))!=1) throw bad("请先设置并保存公司放假日期"); assertFrozenDatesUnchanged(dates,dateValues("SELECT holiday_date FROM oa_attendance_user_holiday WHERE rule_year_id=? AND revision=? AND user_id=?",id,rev,userId));
        db.update("DELETE FROM oa_attendance_user_holiday WHERE rule_year_id=? AND revision=? AND user_id=?",id,rev,userId);
        dates.forEach(d->db.update("INSERT INTO oa_attendance_user_holiday(rule_year_id,company_id,revision,user_id,holiday_date,created_by) VALUES(?,?,?,?,?,?)",id,user.companyId(),rev,userId,d,user.id()));
        db.update("""
            INSERT INTO oa_attendance_user_rule_config(rule_year_id,company_id,revision,user_id,implicit_holiday_credit,baseline_date,configured_by,configured_at)
            VALUES(?,?,?,?,?,?,?,NOW())
            ON DUPLICATE KEY UPDATE configured_by=VALUES(configured_by)
            """,id,user.companyId(),rev,userId,initialImplicitHolidayCredit(year),LocalDate.now(clock),user.id());
        db.update("UPDATE oa_attendance_rule_year SET status='DRAFT',version=version+1,updated_by=? WHERE id=?",user.id(),id); log(user,id,rev,"SAVE_USER",userId,dates.stream().map(LocalDate::toString).toList()); return getUser(user,year,userId);
    }

    @Transactional
    public Map<String,Object> publish(ManagementUser user,int year,VersionInput input){
        requireAdmin(user); Map<String,Object> head=lockExisting(user,year); ensureVersion(head,input==null?null:input.version()); if(num(head.get("companyConfigured"))!=1)throw bad("请先设置公司放假日期"); long id=num(head.get("id"));int rev=num(head.get("draftRevision"));List<Map<String,Object>> candidates=db.queryForList("""
            SELECT u.display_name userName,COUNT(h.id) holidayCount,cfg.user_id configuredUserId
            FROM sys_user_company uc JOIN sys_user u ON u.id=uc.user_id AND u.status='ENABLED' AND u.deleted_at IS NULL
            LEFT JOIN oa_attendance_user_rule_config cfg ON cfg.rule_year_id=? AND cfg.revision=? AND cfg.user_id=u.id
            LEFT JOIN oa_attendance_user_holiday h ON h.rule_year_id=? AND h.revision=? AND h.user_id=u.id AND h.holiday_date>?
            WHERE uc.company_id=? AND uc.status='ENABLED' AND uc.deleted_at IS NULL
            GROUP BY u.id,u.display_name,cfg.user_id ORDER BY u.display_name,u.id
            """,id,rev,id,rev,LocalDate.now(clock),user.companyId());List<Map<String,Object>> invalidUsers=new ArrayList<>();for(Map<String,Object> row:candidates){long targetId=((Number)row.get("configuredUserId")==null)?0:((Number)row.get("configuredUserId")).longValue();int required=targetId==0?requiredPersonalHolidayCount(year):requiredPersonalHolidayCount(year,id,rev,targetId);if(targetId==0||num(row.get("holidayCount"))!=required){row.put("requiredCount",required);invalidUsers.add(row);}}if(!invalidUsers.isEmpty()){String examples=invalidUsers.stream().limit(5).map(r->r.get("userName")+"（当前"+r.get("holidayCount")+"天/应"+r.get("requiredCount")+"天）").collect(java.util.stream.Collectors.joining("、"));throw bad("每位员工剩余个人放假必须按首次配置基线补足；待完善："+examples+(invalidUsers.size()>5?"等"+invalidUsers.size()+"人":""));}List<Map<String,Object>> conflicts=conflicts(id,rev);if(!conflicts.isEmpty())throw bad("仍有个人放假与公司放假冲突，请先完成调整");
        db.update("UPDATE oa_attendance_rule_year SET status='PUBLISHED',published_revision=draft_revision,published_by=?,published_at=NOW(),version=version+1,updated_by=? WHERE id=?",user.id(),user.id(),id);log(user,id,rev,"PUBLISH",null,Set.of());return getYear(user,year);
    }

    private Map<String,Object> ensureDraft(ManagementUser u,Map<String,Object> h){Object pub=h.get("publishedRevision");int rev=num(h.get("draftRevision"));if(pub!=null&&num(pub)==rev){int next=rev+1;long id=num(h.get("id"));db.update("INSERT INTO oa_attendance_company_holiday(rule_year_id,company_id,revision,holiday_date,created_by) SELECT rule_year_id,company_id,?,holiday_date,? FROM oa_attendance_company_holiday WHERE rule_year_id=? AND revision=?",next,u.id(),id,rev);db.update("INSERT INTO oa_attendance_user_holiday(rule_year_id,company_id,revision,user_id,holiday_date,created_by) SELECT rule_year_id,company_id,?,user_id,holiday_date,? FROM oa_attendance_user_holiday WHERE rule_year_id=? AND revision=?",next,u.id(),id,rev);db.update("INSERT INTO oa_attendance_user_rule_config(rule_year_id,company_id,revision,user_id,implicit_holiday_credit,baseline_date,configured_by,configured_at) SELECT rule_year_id,company_id,?,user_id,implicit_holiday_credit,baseline_date,?,configured_at FROM oa_attendance_user_rule_config WHERE rule_year_id=? AND revision=?",next,u.id(),id,rev);db.update("UPDATE oa_attendance_rule_year SET draft_revision=?,status='DRAFT',updated_by=? WHERE id=?",next,u.id(),id);h.put("draftRevision",next);}return h;}
    private Map<String,Object> lockOrCreate(ManagementUser u,int y){List<Map<String,Object>> rows=db.queryForList("SELECT id,status,company_configured companyConfigured,draft_revision draftRevision,published_revision publishedRevision,version FROM oa_attendance_rule_year WHERE company_id=? AND rule_year=? FOR UPDATE",u.companyId(),y);if(rows.isEmpty()){db.update("INSERT INTO oa_attendance_rule_year(company_id,rule_year,created_by,updated_by) VALUES(?,?,?,?)",u.companyId(),y,u.id(),u.id());rows=db.queryForList("SELECT id,status,company_configured companyConfigured,draft_revision draftRevision,published_revision publishedRevision,version FROM oa_attendance_rule_year WHERE company_id=? AND rule_year=? FOR UPDATE",u.companyId(),y);}return new LinkedHashMap<>(rows.get(0));}
    private Map<String,Object> lockExisting(ManagementUser u,int y){Map<String,Object> h=lockOrCreate(u,y);return h;}
    private List<Map<String,Object>> conflicts(long id,int rev){List<Map<String,Object>> rows=db.queryForList("SELECT h.user_id userId,u.display_name userName,DATE_FORMAT(h.holiday_date,'%Y-%m-%d') conflictDate FROM oa_attendance_user_holiday h JOIN oa_attendance_company_holiday c ON c.rule_year_id=h.rule_year_id AND c.revision=h.revision AND c.holiday_date=h.holiday_date JOIN sys_user u ON u.id=h.user_id WHERE h.rule_year_id=? AND h.revision=? ORDER BY u.display_name,h.holiday_date",id,rev);Map<Long,Map<String,Object>> grouped=new LinkedHashMap<>();for(var r:rows){long uid=num(r.get("userId"));var g=grouped.computeIfAbsent(uid,k->{var m=new LinkedHashMap<String,Object>();m.put("userId",uid);m.put("userName",r.get("userName"));m.put("dates",new ArrayList<String>());return m;});((List<String>)g.get("dates")).add(String.valueOf(r.get("conflictDate")));}return new ArrayList<>(grouped.values());}
    private Set<LocalDate> validateDates(int year,DatesInput input){if(input==null||input.dates()==null)throw bad("日期列表不能为空");Set<LocalDate> out=new LinkedHashSet<>();for(String value:input.dates()){try{LocalDate d=LocalDate.parse(value);if(d.getYear()!=year)throw bad("只能选择"+year+"年的日期");out.add(d);}catch(java.time.format.DateTimeParseException e){throw bad("日期格式不正确："+value);}}return out;}
    private void ensureVersion(Map<String,Object> h,Integer expected){if(expected!=null&&expected!=num(h.get("version")))throw new ManagementException(HttpStatus.CONFLICT,"规则已被其他管理员修改，请刷新后重试");}
    private void requireCompanyUser(ManagementUser u,long id){if(db.queryForList("SELECT 1 FROM sys_user_company uc JOIN sys_user s ON s.id=uc.user_id WHERE uc.company_id=? AND uc.user_id=? AND uc.status='ENABLED' AND uc.deleted_at IS NULL AND s.status='ENABLED' AND s.deleted_at IS NULL",u.companyId(),id).isEmpty())throw bad("员工不存在或已停用");}
    private void requireAdmin(ManagementUser u){if(u==null||u.companyId()==null||!u.roles().contains("COMPANY_ADMIN"))throw new ManagementException(HttpStatus.FORBIDDEN,"仅公司管理员可以设置考勤规则");}
    private void validateYear(int year){if(year<2000||year>2100)throw bad("年份不正确");}
    private int requiredPersonalHolidayCount(int year){return remainingPersonalHolidayCount(year,initialImplicitHolidayCredit(year),0);}
    private int requiredPersonalHolidayCount(int year,long ruleId,int revision,long userId){List<Map<String,Object>> rows=db.queryForList("SELECT implicit_holiday_credit implicitCredit,baseline_date baselineDate FROM oa_attendance_user_rule_config WHERE rule_year_id=? AND revision=? AND user_id=?",ruleId,revision,userId);if(rows.isEmpty())return requiredPersonalHolidayCount(year);Map<String,Object> config=rows.get(0);LocalDate baseline=localDate(config.get("baselineDate"));Long elapsed=db.queryForObject("SELECT COUNT(*) FROM oa_attendance_user_holiday WHERE rule_year_id=? AND revision=? AND user_id=? AND holiday_date>? AND holiday_date<=?",Long.class,ruleId,revision,userId,baseline,LocalDate.now(clock));return remainingPersonalHolidayCount(year,num(config.get("implicitCredit")),elapsed==null?0:elapsed);}
    private int initialImplicitHolidayCredit(int year){LocalDate today=LocalDate.now(clock);int annual=java.time.Year.of(year).length()/7;if(year<today.getYear())return annual;if(year>today.getYear())return 0;return Math.min(annual,today.getDayOfYear()/7);}
    static int remainingPersonalHolidayCount(int year,int implicitCredit,long elapsedConfiguredHolidays){int annual=java.time.Year.of(year).length()/7;return Math.max(0,annual-Math.max(0,implicitCredit)-(int)Math.max(0,elapsedConfiguredHolidays));}
    private LocalDate localDate(Object value){if(value instanceof java.sql.Date date)return date.toLocalDate();if(value instanceof LocalDate date)return date;return LocalDate.parse(String.valueOf(value));}
    private void assertFrozenDatesUnchanged(Set<LocalDate> requested,Set<LocalDate> existing){LocalDate cutoff=LocalDate.now(clock);Set<LocalDate> requestedFrozen=requested.stream().filter(d->!d.isAfter(cutoff)).collect(java.util.stream.Collectors.toSet());Set<LocalDate> existingFrozen=existing.stream().filter(d->!d.isAfter(cutoff)).collect(java.util.stream.Collectors.toSet());if(!requestedFrozen.equals(existingFrozen))throw bad(cutoff+"及以前的考勤规则已冻结，不允许修改");}
    private Set<LocalDate> dateValues(String sql,Object...args){Set<LocalDate> out=new LinkedHashSet<>();for(Map<String,Object> row:db.queryForList(sql,args)){Object value=row.values().iterator().next();if(value instanceof java.sql.Date d)out.add(d.toLocalDate());else if(value instanceof LocalDate d)out.add(d);else out.add(LocalDate.parse(String.valueOf(value)));}return out;}
    private List<String> dates(String sql,Object...args){return db.queryForList(sql,String.class,args);}
    private void log(ManagementUser u,long id,int rev,String action,Long target,Object detail){try{db.update("INSERT INTO oa_attendance_rule_change_log(rule_year_id,company_id,revision,action,target_user_id,detail_json,operator_user_id) VALUES(?,?,?,?,?,?,?)",id,u.companyId(),rev,action,target,json.writeValueAsString(detail),u.id());}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private int num(Object v){if(v==null)return 0;if(v instanceof Boolean b)return b?1:0;return ((Number)v).intValue();}
    private ManagementException bad(String m){return new ManagementException(HttpStatus.BAD_REQUEST,m);}
    public record DatesInput(List<String> dates,Integer version){}
    public record VersionInput(Integer version){}
}
