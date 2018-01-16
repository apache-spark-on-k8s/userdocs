# [WIP] Spark Job Submission on K8s 

**DISCLAIMER**: The state of this documents is pre-alpha and [WIP] (Work in Progress). It contains some [TBC] (To Be Checked) and is not intended to be merged in the Spark documentation as it is.

The goal of this document is to review the current submission process in `cluster mode` and introduce a way to also fully support the `client mode` thtat is mandatory for the exploraty projects (notebooks...). We want to ensure that the way the Spark `Driver` and `Executors` lifecycle is correctly understood to take correct decisions to let evolve the architecture. The `Shuffle Service` and `Resource Staging Server` are not impacted by the previous considerations, so we don't cover them.

It also put the K8S way to make dependencies and hadoop configuration available in perspective and compare it to YARN. This topic could be considered as orthogonal to the submission process but is integral part of it and should be also correctly understood.

We assume you have previous knowledge and experience with Spark, Kubernetes and Hadoop to go through the following points:

+ Review definitions, describe the current submission process, highlight differences between cluster and client modes and point the caveats.
+ Hadoop and Deps download are then covered.
+ How YARN deals with dependencies is described to allow better comparison.
+ We finish with a few notes.

## Definitions

Spark support 2 types of `deploy mode`.

1. `client-mode`: The Spark client mode submission where the given class is directly run.
2. `cluster-mode`: The Spark cluster mode submission creates first a `Client` object (the Submission Client) which is responsible to create an external Driver process that will in its turn run the given class.

Spark proposes 2 ways of interacting.

1. `spark-submit` takes an application and a class to run. It creates a Driver which starts the given class and ensures that Executors are created to support the Spark actions. Once the class is executed by the executors is finished, the application exits.
2. `spark-shell` allows the user to have a REPL (via shell, via notebook...). It is a special case of spark-submit where the class if o.a.s.r.Main and the jar is the spark-repl-....jar.

On Kubernetes side, the process can be created at 2 different places.

1. `in-cluster`: This will always be the case the Executors.
2. `out-cluster`: In some case, the Submission Client or the Driver can 

For now, the resource managers can be `Kubernetes`, `YARN` or `Mesos`. We focuss on Kubernetes and compare to YARN as the most widely used on the market. The user gives a `master-url` to choose and specify which resource manager should be used.

We have 6 different scenarii to consider.

1. spark-submit cluster-mode in-cluster
2. spark-submit cluster-mode out-cluster
3. spark-submit client-mode in-cluster
4. spark-submit client-mode out-cluster
5. spark-shell client-mode in-cluster
6. spark-shell client-mode out-cluster

The support functions we want to cover are.

+ Full support of Spark stack (core, sql, ml, streamin...)
+ Ability to make external jars and files available in Driver and Executors.
+ Access to secured Hadoop from Driver and Executor.
+ Ability to hook to mount and configure other behavior (think to connecting to an Etcd cluster).

We want to achieve these with the minimal impact on the non-kubernetes Spark modules.

## Submission Process

It all starts with `SparkSubmit` in spark-core (we only consider here the `Submit` action, not the `Kill` nor `Request_Status`).

The `prepareSubmitEnvironment` method, depending on the given `master-url` and the given `deploy-mode`, mainly ensures that the dependencies (Java, Python, R) are made available in a temp place on HDFS, taken into account the jars and other resources defined via `--jars` `--files` `--packages` `--py-files`. The important point at this stage is the idea of putting the requested jars (the application jar itself, and the other jars if any) in HDFS and a correct classpath setup.

The `downloadFile` method will resolve the given files and jars printing on the console `(s"Downloading ${uri.toString} to ${tmpFile.getAbsolutePath}.")` (you can see that when you submit the job).

```scala
private[deploy] def downloadFile(path: String, hadoopConf: HadoopConfiguration): String = {
  require(path != null, "path cannot be null.")
  val uri = Utils.resolveURI(path)
  uri.getScheme match {
    case "file" | "local" =>
      path
    case _ =>
      val fs = FileSystem.get(uri, hadoopConf)
      val tmpFile = new File(Files.createTempDirectory("tmp").toFile, uri.getPath)
      // scalastyle:off println
      printStream.println(s"Downloading ${uri.toString} to ${tmpFile.getAbsolutePath}.")
      // scalastyle:on println
      fs.copyToLocalFile(new Path(uri), new Path(tmpFile.getAbsolutePath))
      Utils.resolveURI(tmpFile.getAbsolutePath).toString
  }
}
```

*[TBC] Is the downloadFile also invoke in case of K8S? I don't see any tmp files in my HDFS...!*

Now time to invoke the main method of the given class with `mainMethod.invoke(null, childArgs.toArray)`.

```scala
try {
  mainMethod.invoke(null, childArgs.toArray)
} catch {
```

Most important here, The invoked method depends on the deploy mode. This is described here after.

### Cluster Mode

For `cluster-mode`, the method to run will be the chosen resource manager Submission Client. In case of Kubernetes, it will be `org.apache.spark.deploy.k8s.submit.Client.main`

The Submission Client will ensure that an Configuration Step is created and applied to configure in some way the Driver Pod and its Init Container (think to configmaps...).

```scala
new Client(
    configurationStepsOrchestrator.getAllConfigurationSteps(),
    sparkConf,
    kubernetesClient,
    waitForAppCompletion,
    appName,
    loggingPodStatusWatcher).run()
}
```

Once the definitions made, the Driver Pod is created and run the given end user class (e.g. `org.apache.spark.examples.SparkPi`). The launch scrip of the Docker driver will setup the inital classpath (we then fall in the following scenario where the end user class is run).

### Client Mode

However, in case of `client-mode`, the method to run will be the main method of the end user given class, e.g. `org.apache.spark.examples.SparkPi.main`.

That method is expected to `getOrCreate` a `SparkSession` which will in its turn create a `SparkContext` and `SparkConfig`.

Depending on the given `master-url` (which defines the resource manager), `SparkContext.createTaskScheduler` will instanciate the ah-hoc `ClusterManager`, `TaskScheduler`. and `BackendScheduler`.

```scala
cm = getClusterManager(masterUrl) // In K8S case the org.apache.spark.scheduler.cluster.k8s.KubernetesClusterManager
val scheduler = cm.createTaskScheduler(sc, masterUrl) // In K8S case the org.apache.spark.scheduler.cluster.k8s.KubernetesTaskSchedulerImp
val backend = cm.createSchedulerBackend(sc, masterUrl, scheduler) // In K8S case the org.apache.spark.scheduler.cluster.k8s.KubernetesClusterSchedulerBackend
```

We are now ready to Spark on K8S, but we still need to take care on the deploy mode (cluser or client) and place where we sit (In- or Out-Cluster).

## Client Mode

In `client mode`, the Driver (the given class) runs in the same Java process and the executors will be created in K8S Pods.

This implies that the Driver can be created in a Pod or not, depending on where you launch the spark command. The Executors will always be instanciated in a Pod.

It is important to acknowledge this as during the creation of the manager and schedulers described in earlier point, some definitions are created and some communication with the Kubernetes cluster (the REST API) are instanciated.

In the `InCluster` case, as pre-requisite to the next steps, we ask the user to define a property `spark.kubernetes.driver.pod.name` with the value being the exact name of the Pod where he is.

*[TBC] In this acceptable in first instance. We can always automatically detect this in the future - Question: How do get the Pod name where we are via the API?*

With the presence or not of the `/var/run/secrets/kubernetes.io/serviceaccount/token` file, we can determine if we are in a Pod or not.

The place to decide this is the `org.apache.spark.deploy.k8s.SparkKubernetesClientFactory`. We have implemented the private `createInClusterKubernetesClient` and `createOutClusterKubernetesClient methods`. The public `createKubernetesClient` is solely responsible for the external callers to determine which one of the 2 private methods to use.

However, this is not enough. We also have 4 other places where decisions must be taken. The decisions are implemented with Handlers that determine behavior depemdening on the in/out cluster and client/cluster mode parameters.

The second impacted place is the `KubernetesClusterManager` which segregates the In from the Out cluster. For OutCluster, the given spark master URL must be used, while for the `InCLuster`, we use the internal https://kubernetes:433 URL.

```scala
trait ManagerSpecificHandlers {
  def createKubernetesClient(sparkConf: SparkConf): KubernetesClient
}
class InClusterHandlers extends ManagerSpecificHandlers {
  override def createKubernetesClient(sparkConf: SparkConf): KubernetesClient =
    SparkKubernetesClientFactory.createKubernetesClient(
      KUBERNETES_MASTER_INTERNAL_URL,
      Some(sparkConf.get(KUBERNETES_NAMESPACE)),
      APISERVER_AUTH_DRIVER_MOUNTED_CONF_PREFIX,
      sparkConf,
      Some(new File(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH)),
      Some(new File(Config.KUBERNETES_SERVICE_ACCOUNT_CA_CRT_PATH)))
}
class OutClusterHandlers extends ManagerSpecificHandlers {
  override def createKubernetesClient(sparkConf: SparkConf): KubernetesClient =
    SparkKubernetesClientFactory.createKubernetesClient(
      sparkConf.get("spark.master").replace("k8s://", ""),
      Some(sparkConf.get(KUBERNETES_NAMESPACE)),
      APISERVER_AUTH_DRIVER_MOUNTED_CONF_PREFIX,
      sparkConf,
      Some(new File(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH)),
      Some(new File(Config.KUBERNETES_SERVICE_ACCOUNT_CA_CRT_PATH)))
}
```

*[TBC] Make a bit more compact that code having a common method that receives the `master-url` as parameter.*

The third place is the `SchedulerBackendSpecificHandlers` which segregates the OutClientModeCluster (out cluster + client mode) from the rest. For OutClientModeCluster, the driver pod as the driver pod name are null.

```scala
trait SchedulerBackendSpecificHandlers {
  def getDriverPod(): Pod
  def getKubernetesDriverPodName(conf: SparkConf): String
}
class OutClusterClientModeHandlers extends SchedulerBackendSpecificHandlers {
  override def getDriverPod(): Pod = null
  override def getKubernetesDriverPodName(conf: SparkConf): String = null
}

class NonOutClusterClientModeHandlers extends SchedulerBackendSpecificHandlers {
  override def getDriverPod(): Pod = {
    try {
      kubernetesClient.pods().inNamespace(kubernetesNamespace).
        withName(kubernetesDriverPodName).get()
    } catch {
      case throwable: Throwable =>
        logError(s"Executor cannot find driver pod.", throwable)
        throw new SparkException(s"Executor cannot find driver pod", throwable)
    }
  }
  override def getKubernetesDriverPodName(conf: SparkConf): String = {
    conf
      .get(KUBERNETES_DRIVER_POD_NAME)
      .getOrElse(
        throw new SparkException("Must specify the driver pod name"))
  }
}
```

The fourth place is `org.apache.spark.scheduler.cluster.k8s.ExecutorPodFactoryImpl` which is impacted during Executors creation. With Executors, we are for sure in a Pod but we may have no Driver Pod to refer to (in `cluster-mode`, we have a driver Pod to refer to).

*Question What is the use of the owner reference?*

```scala
val executorPod = (driverPod == null) match {
  case true => new PodBuilder()
    .withNewMetadata()
      .withName(name)
      .withLabels(resolvedExecutorLabels.asJava)
      .withAnnotations(executorAnnotations.asJava)
    .endMetadata()
    .withNewSpec()
      .withHostname(hostname)
      .withRestartPolicy("Never")
      .withNodeSelector(nodeSelector.asJava)
      .addAllToVolumes(shuffleVolumesWithMounts.map(_._1).asJava)
      .endSpec()
    .build()
  case false => new PodBuilder()
    .withNewMetadata()
      .withName(name)
      .withLabels(resolvedExecutorLabels.asJava)
      .withAnnotations(executorAnnotations.asJava)
      .withOwnerReferences()
      .addNewOwnerReference()
        .withController(true)
        .withApiVersion(driverPod.getApiVersion)
        .withKind(driverPod.getKind)
        .withName(driverPod.getMetadata.getName)
        .withUid(driverPod.getMetadata.getUid)
        .endOwnerReference()
      .endMetadata()
    .withNewSpec()
    .withHostname(hostname)
    .withRestartPolicy("Never")
    .withNodeSelector(nodeSelector.asJava)
    .addAllToVolumes(shuffleVolumesWithMounts.map(_._1).asJava)
    .endSpec()
  .build()
}
```

We still have an fifth place to fix an issue. The Pod names in client mode are always `spark-exec-<id>` which gives issues in case multiple `client-mode` users on the K8S cluster or in case on non-terminated pods. This is logical as the `spark.kubernetes.executor.podNamePrefix` is not set (part of the driver orchestrator process not executed in `client-mode`).

The proposed fix is here.

```scala
val prefix = (executorPodNamePrefix == "spark") match {
  case true => {
    val appName = sparkConf.getOption("spark.app.name").getOrElse("spark")
    val launchTime = System.currentTimeMillis()
    s"$appName-$launchTime".toLowerCase.replaceAll("\\.", "-")
  } // We are in client mode.
  case false => executorPodNamePrefix //  We are in cluster mode.
}
val name = s"$prefix-exec-$executorId"
```

*[TBC] We want to introduce a way to normalize Pod names in case of space of special characters - This should be applied to both Driver and Executor names - @see [https://github.com/apache-spark-on-k8s/spark/issues/551](#551)*

# Client Mode Caveats

In `cluster-mode`, the Submission Client creates the configuration orchestrator, ensuring the Pods are well configured.

```scala
val configurationStepsOrchestrator = new DriverConfigurationStepsOrchestrator(
  namespace,
  kubernetesAppId,
  launchTime,
  clientArguments.mainAppResource,
  appName,
  clientArguments.mainClass,
  clientArguments.driverArgs,
  clientArguments.otherPyFiles,
  clientArguments.hadoopConfDir,
  sparkConf)
```

We don't have this phase in `client-mode` where the user class is directly run (we don't go via the Submission Client), so the Driver configuration steps are not applied.

At first sight, this may sound tricky, but finally we don't have any Driver Pod to configure, so we can not do much for it...

However, we must ensure that Dependencies are downloaded and Hadoop still works fine which is the case (see the reasons in next sections), but we may be fully aware of this to avoid issues in the future caused by the lack of configuration.

Please note that, as the Driver configuration steps are not applied, the Headless Driver is also not created.

If we really want to apply some configuration say e.g. on the Executors, one option would to hook in e.g. the `KubernetesClusterManager` or still better to use the `executorInitContainerBootstrap` to achieve the needed job (if any...).

*[TBC] This is the main point to be discussed... - As first instance, we can don't need the Driver configuration steps*

*[TBC]: Note on performance: At first sight, it seems the `client-mode` is a bit slower than the `cluster-mode` for actions only run on driver side - To be benchmarked...*

# Hadoop

We want to access HDFS from Driver and Executor in whatever state we are.

In `cluster-mode`, this is achived mounting an existing hadoop `configmap` in the Driver. A new confimap is created based on the given one with that code in the `DriverConfigurationStepsOrchestrator`. This is in a way needed as the driver is not created via a end-user controlled process, so the end-user can not enforce its own configmaps on the Driver pod. Spark must in a way propagate the configmaps.

```scala
val hadoopConfigSteps =
  hadoopConfDir.map { conf =>
    val hadoopStepsOrchestrator =
      new HadoopStepsOrchestrator(
        kubernetesResourceNamePrefix,
        namespace,
        hadoopConfigMapName,
        submissionSparkConf,
        conf)
```

However, in `cluster-mode`, we simply have to ensure that the Pod where we start the Spark process is created with a correct Hadoop configmap. "By magic", the Executors benefit from that configuration (you can check this with  `spark.sparkContext.hadoopConfiguration`) and Spark can access HDFS in a distributed way.

```scala
spark.sparkContext.hadoopConfiguration.get("fs.defaultFS")
res19: String = hdfs://hdfs-k8s-hdfs-k8s-hdfs-nn:9000/
```

Please not the slight difference if you isntanciate your self the Hadoop Config (this may be a limitation of the `client-mode` if you want to directly address the Hadoop API from within an Executor).

```scala
// Via cluster mode, we have the full config as the hadoop conf dir is mounted by configmap propagation.
(1 to 3).toDS.coalesce(3).map(_ => { new org.apache.hadoop.conf.Configuration().get("fs.defaultFS")}).collect
res0: Array[String] = Array(hdfs://hdfs-k8s-hdfs-k8s-hdfs-nn:9000/, hdfs://hdfs-k8s-hdfs-k8s-hdfs-nn:9000/, hdfs://hdfs-k8s-hdfs-k8s-hdfs-nn:9000/)
```

```scala
// Via cluster mode, we do not have full config as the hadoop conf dir is not mounted by configmap propagation but only available in the Spark Context.
(1 to 3).toDS.coalesce(3).map(_ => { new org.apache.hadoop.conf.Configuration().get("fs.defaultFS")}).collect
res16: Array[String] = Array(file:///, file:///, file:///)
```

# Dependencies

For `cluster-mode`, we have the configuration orchestrator and its Steps which ensure that when `--jars` or `-Dspark.jars` are defined (same reaseonaring applies for `--files`):

+ In case of local resource, the Docker image has to ship the them at the given path.
+ in case of non-local resource (hdfs, http, s3a...), the init container will request the downlod to the resource staging server and will put them in the `/var/spark-data/spark-jars` folder.

At the end, we have some mounted classpath and files directory under `/var/spark-data` folder.

```console
SPARK_MOUNTED_CLASSPATH: /var/spark-data/spark-jars/*
SPARK_MOUNTED_FILES_DIR: /var/spark-data/spark-files
```

The `/var/spark-data` folders are populated on Pod creation (no need to run a Spark action).

If we disable the init container, the external jars will be in the Executors via the `sc.addJar` mechanism implemented by spark-core which makes jars available via `spark://...` URLs resolved via the `NettyRpc` (see also details in the `client-mode` description here after).

```
2018-01-16 04:53:33 INFO  SparkContext:54 - Added JAR http://central.maven.org/maven2/org/apache/hbase/hbase-common/1.4.0/hbase-common-1.4.0.jar at http://central.maven.org/maven2/org/apache/hbase/hbase-common/1.4.0/hbase-common-1.4.0.jar 
with timestamp 1516078413972
2018-01-16 04:53:33 INFO  SparkContext:54 - Added JAR /opt/spark/examples/jars/spark-examples_2.11-2.2.0-k8s-0.5.0.jar at spark://test-1516078409213-driver-svc.default.svc.cluster.local:7078/jars/spark-examples_2.11-2.2.0-k8s-0.5.0.jar wit
```

However, those jars will **not** be in the Driver.

*[TBC] Having a way to download jars on the driver and from any other source (not only HDFS supported protocols) makes a strong argument to keep the init containers.*

In `client-mode` we don't have the driver configuration orchestrator, hence not the init-container on driver level, but because we start the process in the same JVM, we can use e.g. `spark.jars` or `spark.driver.extraClassPath` to request the driver to ship the jars to the executors. In this case, the jars will reside under the `/opt/spark/work-dir` folder. The `/var/spark-data/spark-jars` remains of course empty as not populated by any init container. Please note that only on invocation of a Spark action (not a definition), when the Executor is effectively sollicated after its creation, that the jars will appear in the `/opt/spark/work-dir` folder.

Spark puts the jars in the current folder specified in the base Docker image which is `/opt/spark/work-dir`.

```
Step 9/10 : WORKDIR /opt/spark/work-dir
```

spark-core side will put the jars defined by `spark.jars` via the SparkContext addJar (using Utils.getUserJars).

```console
 INFO [2018-01-15 14:06:30,468] ({pool-2-thread-2} ContextHandler.java[doStart]:781) - Started o.s.j.s.ServletContextHandler@2f662c2f{/static/sql,null,AVAILABLE,@Spark}
 INFO [2018-01-15 14:06:31,119] ({pool-2-thread-2} Logging.scala[logInfo]:54) - Registered StateStoreCoordinator endpoint
 INFO [2018-01-15 14:06:31,123] ({pool-2-thread-2} SparkInterpreter.java[createSparkSession]:410) - Created Spark session
 INFO [2018-01-15 14:06:41,481] ({pool-2-thread-2} Logging.scala[logInfo]:54) - Added JAR /opt/spitfire/local-repo/2CBEJNFR7/hadoop-aws-2.9.0-palantir.4.jar at spark://192.168.189.239:42823/jars/hadoop-aws-2.9.0-palantir.4.jar with timestamp 1516025201481
 INFO [2018-01-15 14:06:41,482] ({pool-2-thread-2} SparkInterpreter.java[open]:917) - sc.addJar(/opt/spitfire/local-repo/2CBEJNFR7/hadoop-aws-2.9.0-palantir.4.jar)
 INFO [2018-01-15 14:06:41,483] ({pool-2-thread-2} Logging.scala[logInfo]:54) - Added JAR /opt/spitfire/local-repo/2CBEJNFR7/aws-java-sdk-bundle-1.11.201.jar at spark://192.168.189.239:42823/jars/aws-java-sdk-bundle-1.11.201.jar with timestamp 1516025201483
 INFO [2018-01-15 14:06:41,485] ({pool-2-thread-2} SparkInterpreter.java[open]:917) - sc.addJar(/opt/spitfire/local-repo/2CBEJNFR7/aws-java-sdk-bundle-1.11.201.jar)
 INFO [2018-01-15 14:06:41,493] ({pool-2-thread-2} SparkInterpreter.java[populateSparkWebUrl]:980) - Sending metadata to Zeppelin server: {message=Spark UI enabled, url=http://192.168.189.239:4040}
 INFO [2018-01-15 14:06:51,465] ({pool-2-thread-2} Logging.scala[logInfo]:54) - Parsing command: bank
 INFO [2018-01-15 14:06:52,011] ({pool-2-thread-2} SchedulerFactory.java[jobFinished]:115) - Job 20180115-140548_1964460 finished by scheduler org.apache.zeppelin.spark.SparkInterpreter1659571498
```

```scala
...
   _jars = Utils.getUserJars(_conf)
...
    // Add each JAR given through the constructor
    if (jars != null) {
      jars.foreach(addJar)
    }
...
// SparkContext
  /**
   * Adds a JAR dependency for all tasks to be executed on this `SparkContext` in the future.
   * @param path can be either a local file, a file in HDFS (or other Hadoop-supported filesystems),
   * an HTTP, HTTPS or FTP URI, or local:/path for a file on every worker node.
   */
  def addJar(path: String) {
...
      val timestamp = System.currentTimeMillis
      if (addedJars.putIfAbsent(key, timestamp).isEmpty) {
        logInfo(s"Added JAR $path at $key with timestamp $timestamp")
        postEnvironmentUpdate()
      }
```

```scala
// Utils
  /**
   * In YARN mode this method returns a union of the jar files pointed by "spark.jars" and the
   * "spark.yarn.dist.jars" properties, while in other modes it returns the jar files pointed by
   * only the "spark.jars" property.
   */
  def getUserJars(conf: SparkConf, isShell: Boolean = false): Seq[String] = {
    val sparkJars = conf.getOption("spark.jars")
    if (conf.get("spark.master") == "yarn" && isShell) {
      val yarnJars = conf.getOption("spark.yarn.dist.jars")
      unionFileLists(sparkJars, yarnJars).toSeq
    } else {
      sparkJars.map(_.split(",")).map(_.filter(_.nonEmpty)).toSeq.flatten
    }
  }
```

*[TBC] Explain why the jars are available in the Driver for `cient-mode`*

# YARN Dependencies

In the YARN case:

+ We have a `YarnClusterManager`, a `YarnScheduler` and a `YarnSchedulerBackend`.
+ `org.apache.spark.scheduler.cluster.YarnClientSchedulerBackend.start` calls `org.apache.spark.deploy.yarn.Client.submitApplication`.
+ `org.apache.spark.deploy.yarn.Client.createContainerLaunchContext` will then put all needed resource in the HDFS home folder under `.sparkStaging` folder.

Please note that at this stage we are still on the host having submitted the Spark command.

See also:

```scala
  /**
   * Set up the environment for launching our ApplicationMaster container.
   */
  private def setupLaunchEnv(
```

```scala
  /**
   * Upload any resources to the distributed cache if needed. If a resource is intended to be
   * consumed locally, set up the appropriate config for downstream code to handle it properly.
   * This is used for setting up a container launch context for our ApplicationMaster.
   * Exposed for testing.
   */
  def prepareLocalResources(
```

The Application master command where we clearly see the uploaded resources and the creation of the driver and executors.

```console
command:
    {{JAVA_HOME}}/bin/java \ 
...
      --user-class-path \ 
      file:$PWD/hbase-common-1.4.0.jar \ 
      1><LOG_DIR>/stdout \ 
      2><LOG_DIR>/stderr

  resources:
    __spark_libs__ -> resource { scheme: "hdfs" host: "datalayer-001.datalayer.io.local" port: 9000 file: "/user/datalayer/.sparkStaging/application_1515858696776_0002/__spark_libs__2246976798445831703.zip" } size: 232012522 timestamp: 1515858903550 type: ARCHIVE visibility: PRIVATE
    hbase-common-1.4.0.jar -> resource { scheme: "hdfs" host: "datalayer-001.datalayer.io.local" port: 9000 file: "/user/datalayer/.sparkStaging/application_1515858696776_0002/hbase-common-1.4.0.jar" } size: 619037 timestamp: 1515858904173 type: FILE visibility: PRIVATE
    __spark_conf__ -> resource { scheme: "hdfs" host: "datalayer-001.datalayer.io.local" port: 9000 file: "/user/datalayer/.sparkStaging/application_1515858696776_0002/__spark_conf__.zip" } size: 50766 timestamp: 1515858904264 type: ARCHIVE visibility: PRIVATE
```

The list of our HDFS home directory shows us that YARN has uploaded the jars and conf files he will need.

```console
Found 3 items
-rw-r--r--   1 datalayer supergroup      50765 2018-01-13 17:57 /user/datalayer/.sparkStaging/application_1515858696776_0011/__spark_conf__.zip
-rw-r--r--   1 datalayer supergroup  232012522 2018-01-13 17:57 /user/datalayer/.sparkStaging/application_1515858696776_0011/__spark_libs__320488134561245276.zip
-rw-r--r--   1 datalayer supergroup     619037 2018-01-13 17:57 /user/datalayer/.sparkStaging/application_1515858696776_0011/hbase-common-1.4.0.jar
```

Details of those files.

```console
__spark_conf__/
total 88
drwxrwxr-x  2 datalayer datalayer  4096 Jan 13 18:00 ./
drwxr-xr-x 12 datalayer datalayer 12288 Jan 13 18:00 ../
-rw-rw-r--  1 datalayer datalayer  3899 Jan 13 17:57 capacity-scheduler.xml
-rw-rw-r--  1 datalayer datalayer  1326 Jan 13 17:57 configuration.xsl
-rw-rw-r--  1 datalayer datalayer  2188 Jan 13 17:57 core-site.xml
-rw-rw-r--  1 datalayer datalayer  1755 Jan 13 17:57 hadoop-metrics2.properties
-rw-rw-r--  1 datalayer datalayer  3288 Jan 13 17:57 hadoop-metrics.properties
-rw-rw-r--  1 datalayer datalayer 10064 Jan 13 17:57 hadoop-policy.xml
-rw-rw-r--  1 datalayer datalayer  2089 Jan 13 17:57 hdfs-site.xml
-rw-rw-r--  1 datalayer datalayer 11238 Jan 13 17:57 log4j.properties
-rw-rw-r--  1 datalayer datalayer  3180 Jan 13 17:57 mapred-site.xml
-rw-rw-r--  1 datalayer datalayer  1280 Jan 13 17:57 __spark_conf__.properties
-rw-rw-r--  1 datalayer datalayer  2316 Jan 13 17:57 ssl-client.xml
-rw-rw-r--  1 datalayer datalayer  2268 Jan 13 17:57 ssl-server.xml
-rw-rw-r--  1 datalayer datalayer  4131 Jan 13 17:57 yarn-site.xml
```

```console
 __spark_libs__320488134561245276/
-rw-rw-r--  1 datalayer datalayer    69409 Jan 13 17:57 activation-1.1.1.jar
-rw-rw-r--  1 datalayer datalayer   445288 Jan 13 17:57 antlr-2.7.7.jar
-rw-rw-r--  1 datalayer datalayer   302248 Jan 13 17:57 antlr4-runtime-4.5.3.jar
...
-rw-rw-r--  1 datalayer datalayer  8270638 Jan 13 17:57 spark-catalyst_2.11-2.2.0-k8s-0.5.0.jar
...
-rw-rw-r--  1 datalayer datalayer    46566 Jan 13 17:57 spark-unsafe_2.11-2.2.0-k8s-0.5.0.jar
-rw-rw-r--  1 datalayer datalayer   696193 Jan 13 17:57 spark-yarn_2.11-2.2.0-k8s-0.5.0.jar
...
-rw-rw-r--  1 datalayer datalayer    35518 Jan 13 17:57 zjsonpatch-0.3.0.jar
-rw-rw-r--  1 datalayer datalayer   792964 Jan 13 17:57 zookeeper-3.4.6.jar
```

In case of spark-submit (client or cluster mode), the process is the same and generates the same folder structure.

```console
-rw-r--r--   1 datalayer supergroup      50608 2018-01-13 18:13 /user/datalayer/.sparkStaging/application_1515858696776_0016/__spark_conf__.zip
-rw-r--r--   1 datalayer supergroup  232012522 2018-01-13 18:13 /user/datalayer/.sparkStaging/application_1515858696776_0016/__spark_libs__3393918138649389825.zip
-rw-r--r--   1 datalayer supergroup     619037 2018-01-13 18:13 /user/datalayer/.sparkStaging/application_1515858696776_0016/hbase-common-1.4.0.jar
-rw-r--r--   1 datalayer supergroup    1991211 2018-01-13 18:13 /user/datalayer/.sparkStaging/application_1515858696776_0016/spark-examples_2.11-2.2.0-k8s-0.5.0.jar
```

*[TBC] Explaine this log: 2018-01-13 12:15:27 WARN  SparkConf:66 - In Spark 1.0 and later spark.local.dir will be overridden by the value set by the cluster manager (via SPARK_LOCAL_DIRS in mesos/standalone and LOCAL_DIRS in YARN).*

# Notes

## K8S compared to YARN

YARN resource manager uploads from the submssion process to HDFS the jars and files. The YARN framework has built-in capability to use in the YARN containers the defined resources.

K8S reource manager delegates to the init container the download of the jars and files. It reads the given spark.jars property and get them directly from HDFS for example.

+ Deps distribution: Download deps only if needed.
+ spark-core vs init-container: ...
+ Need HDFS: ...
+ Spark susbmission not connected on HDFS: ...
+ Other distribution cache: ...
+ Hadoop conf dir: Hadoop conf must not be present before hand on nodes and can be mounted from configmaps.

### Submit in client mode from a client with restricted network access

This is not possible. When the executor is invoked.

```
[Stage 0:>                                                          (0 + 0) / 2]
2018-01-13 14:58:46 WARN  KubernetesTaskSchedulerImpl:66 - Initial job has not accepted any resources; check your cluster UI to ensure that workers are registered and have sufficient resources
2018-01-13 14:59:01 WARN  KubernetesTaskSchedulerImpl:66 - Initial job has not accepted any resources; check your cluster UI to ensure that workers are registered and have sufficient resources

2018-01-13 13:47:25 INFO  SecurityManager:54 - SecurityManager: authentication disabled; ui acls disabled; users  with view permissions: Set(root); groups with view permissions: Set(); users  with modify permissions: Set(root); groups with modify permissions: Set()
Exception in thread "main" java.lang.reflect.UndeclaredThrowableException
	at org.apache.hadoop.security.UserGroupInformation.doAs(UserGroupInformation.java:1904)
	at org.apache.spark.deploy.SparkHadoopUtil.runAsSparkUser(SparkHadoopUtil.scala:66)
	at org.apache.spark.executor.CoarseGrainedExecutorBackend$.run(CoarseGrainedExecutorBackend.scala:188)
	at org.apache.spark.executor.CoarseGrainedExecutorBackend$.main(CoarseGrainedExecutorBackend.scala:284)
	at org.apache.spark.executor.CoarseGrainedExecutorBackend.main(CoarseGrainedExecutorBackend.scala)
2018-01-13 13:49:26 ERROR RpcOutboxMessage:70 - Ask timeout before connecting successfully
Caused by: org.apache.spark.rpc.RpcTimeoutException: Cannot receive any reply from 192.168.1.7:36097 in 120 seconds. This timeout is controlled by spark.rpc.askTimeout
	at org.apache.spark.rpc.RpcTimeout.org$apache$spark$rpc$RpcTimeout$$createRpcTimeoutException(RpcTimeout.scala:47)
	at org.apache.spark.rpc.RpcTimeout$$anonfun$addMessageIfTimeout$1.applyOrElse(RpcTimeout.scala:62)
	at org.apache.spark.rpc.RpcTimeout$$anonfun$addMessageIfTimeout$1.applyOrElse(RpcTimeout.scala:58)
	at scala.runtime.AbstractPartialFunction.apply(AbstractPartialFunction.scala:36)
	at scala.util.Failure$$anonfun$recover$1.apply(Try.scala:216)
	at scala.util.Try$.apply(Try.scala:192)
	at scala.util.Failure.recover(Try.scala:216)
```

You must have network connectivity between your Submission Client, Driver and Executors.

### Chain Pattern

We could enhance the submit steps applying chain pattern

https://en.wikipedia.org/wiki/Chain-of-responsibility_pattern

### Configurable Chain

Default settings `spark.kubernetes.driver.steps` should contain the current steps.

A user could change this shipping its own custom steps, including adding and disabling some init-container and hadoop steps.

### isKubernetesCluster in SparkSubmit

In `SparkSubmit` we could replace `isKubernetesCluster` with `isKubernetes` to avoid confusion with cluster and client modes.
