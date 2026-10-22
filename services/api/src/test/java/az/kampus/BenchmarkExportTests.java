package az.kampus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;
import static az.kampus.Store.*;

/**
 * Writes the benchmark that the Python models are measured on: the same generated people, the same answer keys and what the
 * statistical code in Insights says about them. Runs only with -Dbenchmark.out=PATH.
 */
class BenchmarkExportTests {
 @Test void exportBenchmark()throws Exception{
  String out=System.getProperty("benchmark.out");
  Assumptions.assumeTrue(out!=null&&!out.isBlank());
  Instant now=InsightsTests.NOW;
  var root=new LinkedHashMap<String,Object>();
  root.put("now",now.toString());
  var anomalyPeople=new ArrayList<Map<String,Object>>();
  for(long seed=1;seed<=40;seed++)for(int planted:new int[]{3,0}){
   var r=SyntheticSpending.generate(seed,now,6,planted,false);
   var spends=Insights.spending(r.txs(),List.of());
   var found=Insights.anomalies(spends,now,Set.of(),Set.of());
   anomalyPeople.add(m("seed",seed,"planted",planted,"txs",r.txs(),"anomalyIds",r.anomalyIds(),"recurringKeys",r.recurringKeys(),
    "javaAnomalies",found.stream().map(a->m("txId",a.txId(),"score",a.score(),"reason",a.reason())).toList(),
    "javaRecurring",Insights.recurring(spends,now).stream().map(Insights.Recurring::key).toList()));
  }
  root.put("people",anomalyPeople);
  var forecasts=new ArrayList<Map<String,Object>>();
  for(long seed=1;seed<=30;seed++){
   var full=SyntheticSpending.generate(seed,InsightsTests.MONTH_END,6,0,false);
   var all=Insights.spending(full.txs(),List.of());
   long truth=all.stream().filter(s->s.day().getMonthValue()==10).mapToLong(Insights.Spend::amount).sum();
   var cuts=new ArrayList<Map<String,Object>>();
   for(var cut:List.of(Instant.parse("2026-10-12T20:00:00Z"),Instant.parse("2026-10-20T20:00:00Z"))){
    var known=all.stream().filter(s->!s.at().isAfter(cut)).toList();
    var f=Insights.forecast(known,Insights.recurring(known,cut),cut,null);
    cuts.add(m("cut",cut.toString(),"javaProjected",f.get("projectedMinor"),"javaLow",f.get("lowMinor"),"javaHigh",f.get("highMinor")));
   }
   forecasts.add(m("seed",seed,"truthMinor",truth,"txs",full.txs(),"cuts",cuts));
  }
  root.put("forecasts",forecasts);
  Files.createDirectories(Path.of(out).getParent());
  new ObjectMapper().writeValue(Path.of(out).toFile(),root);
 }
}
