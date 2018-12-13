package streaming.test.session

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.mlsql.session.SessionIdentifier
import org.apache.spark.streaming.BasicSparkOperation
import streaming.core.strategy.platform.SparkRuntime
import streaming.core.{BasicMLSQLConfig, SpecFunctions, StreamingproJobManager, StreamingproJobType}
import streaming.dsl.ScriptSQLExec

/**
  * Created by aston on 2018/11/2.
  */
class SessionSpec extends BasicSparkOperation with SpecFunctions with BasicMLSQLConfig {

  "session close" should "work fine" in {
    withBatchContext(setupBatchContext(batchParamsWithoutHive)) { runtime: SparkRuntime =>
      StreamingproJobManager.init(runtime.sparkSession.sparkContext)
      runtime.getSession("latincross")

      assume(runtime.sessionManager.getOpenSessionCount == 1)

      runtime.sessionManager.closeSession(SessionIdentifier("latincross"))
      assume(runtime.sessionManager.getOpenSessionCount == 0)

      val jobInfo = StreamingproJobManager.getStreamingproJobInfo(
        "latincross", StreamingproJobType.SCRIPT, "test", "select sleep(5000) as output;", -1L
      )

      StreamingproJobManager.asyncRun(runtime.getSession("latincross"), jobInfo, () => {
        runtime.sparkSession.sql("select sleep(50000)").count()
      })

      assume(runtime.sessionManager.getOpenSessionCount == 1)

      while(StreamingproJobManager.getJobInfo.size == 0){
        Thread.sleep(1000)
      }

      StreamingproJobManager.killJob(StreamingproJobManager.getJobInfo.head._2.groupId)

      runtime.sessionManager.closeSession(SessionIdentifier("latincross"))
      assume(runtime.sessionManager.getOpenSessionCount == 0)
      StreamingproJobManager.shutdown
    }
  }

  "sessionPerUser mode create different temporary tables for different user." should "work fine" in {
    withBatchContext(setupBatchContext(batchParamsWithoutHive)) { runtime: SparkRuntime =>
      StreamingproJobManager.init(runtime.sparkSession.sparkContext)
      val testUserA = "usera"
      val testUserB = "userb"

      val sessionA = runtime.getSession(testUserA)
      val sessionB = runtime.getSession(testUserB)

      val contextA = createSSEL(sessionA)
      ScriptSQLExec.parse(""" select 1 as columna as tablea; """, contextA)
      val tablea = sessionA.sql("select * from tablea")
      assert(tablea.collect().size == 1)
      assertThrows[AnalysisException] {
        sessionB.sql("select * from tablea")
      }
    }
  }

  "in sessionPerUser mode, children Session will inherit root Session Configuration." should "work fine" in {
    withBatchContext(setupBatchContext(batchParamsWithoutHive)) { runtime: SparkRuntime =>
      runtime.sparkSession.udf.register("test_udf", (arg: String) => {
        arg + "_append_string"
      })
      StreamingproJobManager.init(runtime.sparkSession.sparkContext)
      val testUserA = "usera"

      val sessionA = runtime.getSession(testUserA)

      val contextA = createSSEL(sessionA)
      ScriptSQLExec.parse(""" select test_udf("string") as columna as tablea; """, contextA)
      val tablea = sessionA.sql("select columna from tablea")
      assert(tablea.collect().size == 1)
      assert(tablea.collect().head.getString(0) == "string_append_string")
    }
  }
}
