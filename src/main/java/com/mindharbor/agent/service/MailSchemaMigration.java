package com.mindharbor.agent.service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;

/** Compatibility migration for installations previously managed by ddl-auto=update. */
@Component
@DependsOn("entityManagerFactory")
public class MailSchemaMigration {
    public MailSchemaMigration(DataSource source) throws SQLException {
        try(Connection c=source.getConnection()) {
            if(!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")) return;
            Map<String,Set<String>> indexes=new HashMap<>();
            try(ResultSet rs=c.getMetaData().getIndexInfo(c.getCatalog(),null,"report_tasks",true,false)) {
                while(rs.next()) {
                    String name=rs.getString("INDEX_NAME"), column=rs.getString("COLUMN_NAME");
                    if(name!=null && column!=null) indexes.computeIfAbsent(name,k->new HashSet<>()).add(column.toLowerCase(Locale.ROOT));
                }
            }
            Set<String> wanted=Set.of("report_id","kind","recipient","reminder_round");
            if(indexes.values().stream().noneMatch(wanted::equals))
                throw new IllegalStateException("Missing mail round unique index; refusing to drop legacy index");
            for(var entry:indexes.entrySet()) {
                if(entry.getValue().equals(Set.of("report_id","kind","recipient"))) {
                    if(!entry.getKey().matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Unexpected index name");
                    try(Statement st=c.createStatement()) { st.execute("ALTER TABLE report_tasks DROP INDEX `"+entry.getKey()+"`"); }
                }
            }
        }
    }
}
