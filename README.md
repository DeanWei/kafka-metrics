# Kafka Metrics

This is a system whose purpose is to aggregate metrics from a topology of Kafka Brokers, Connectors, Mirrors, Stream 
Topologies and other Producer and Consumer applications. It uses InfluxDB as the time series back-end which can then 
be used for example with Grafana front-end or other visualisation and alerting tools. It contains several modules
which can be used in different configurations together or separately serving a range of use cases from simple 
non-intrusive inspection of existing kafka clusters and applications to global scale purpose-built monitoring solutions.

### Contents

1. [Overivew](#overview)
	- [Architecture](#overview)
	- [Basic Scenario](#scenario0) 
	- [Multi-Server Scenario](#scenario1)
	- [Multi-Data-Centre Scenario](#scenario3) 
	- [Multi-Enviornment Scenario](#scenario2)
2. [Modules](#usage-instance)
 	- [Bundled Instance: InfluxDB, Grafana](#usage-instance)
 	- [InfluxDB Loader](#usage-loader) 
    - [Metrics Connect](#usage-connect)
 	- [Metrics Agent](#metrics-agent)
 	- [TopicReporter](#usage-reporter)
	    - [Usage in Kafka Broker, Kafka Prism, Kafka Producer (pre 0.8.2), Kafka Consumer (pre 0.9)](#usage-reporter-kafka-old)
	    - [Usage in Kafka NEW Producer (0.8.2+) and Consumer (0.9+)](#usage-reporter-kafka-new)
	    - [Usage in any application using dropwizard metrics (formerly yammer metrics)](#usage-reporter-dropwizard)
    - [Usage in Samza](#usage-samza)
6. [Configuration](#configuration)
    - [InfluxDB Configuration](#configuration-influxdb)
    - [JMX Scanner Configuration](#configuration-scanner)
    - [Metrics Producer Configuration](#configuration-producer)
    - [Metrics Consumer Configuration](#configuration-consumer)
7. [Operations & Troubleshooting](#operations)
8. [Development](#development)

<a name="overview">
## Overview
</a>

![overview](doc/metrics.png)

There are several ways of how the aggregation of metrics is achieved using one or more modules.  

<a name="scenario0">
### Basic Scenario
</a>

For smaller systems consisting of components on the same network or simply a localhost, direct JMX scanner tasks can be 
configured for each JMX Application. This method doesn't require to include any extra code in the monitored applications 
as long as they already expose JMX MBeans and in a local environment the kafka topic can also be omitted.

![scenario0](doc/kafka-metrics-scenario0.png)

<a name="scenario1">
### Multi-Server Scenario
</a>

For bigger systems, where metrics from several hosts need to be aggregated or in cases where more fault-tolerant 
collection of metrics is required, a combination of pluggable TopicReproter or JMX Metrics Agent and a Kafka Topic can 
be deployed by configuration. The JMX Scanner used in the basic scenario is replaced with InfluxDB Loader which is a 
kafka consumer that reads measurements from the metrics topic and writes them into the InfluxDB.


![scenario1](doc/kafka-metrics-scenario1.png)

<a name="scenario2">
### Multi-Data-Centre Scenario
</a>

For multi-DC, potentially global deployments, where metrics from several disparate clusters need to be collected, each 
cluster has its agent which publishes into a local metrics topic and one of the existing mirroring components 
(Kafka Prism, Kafka Mirror Maker, ...) is deployed to aggregate local metrics topic into a single aggregated stream 
providing a real-time monitoring of the entire system.

![scenario2](doc/kafka-metrics-scenario2.png)

<a name="scenario3">
### Multi-Environment Scenario
</a>

Finally, in the heterogeneous environments, where different kinds of application and infrastructure stacks exist, 
firstly any JMX-Enabled or YAMMER-Enabled application can be plugged by configuration. 

***For non-JVM applications or for JVM applications that do not expose JMX MBeans, there is a work in progress to have 
REST Metrics Agent which can receive http put requests and which can be deployed in all scenarios either with or without 
the metrics topic.***

![scenario3](doc/kafka-metrics-scenario3.png)

<a name="usage-instance">
### Bundled Instance 
</a>

This module is just a set of installer and launcher scripts that manage local instances of InfluxDB and Grafana. 
This module can be used with all the scenarios whether for testing on development machine or deployed on a production 
host but doesn't have to be used if you have existing InfluxDB component running. 

Provided you have `npm` and `grunt` installed on, the following command should install all components:

```
./gradlew :instance:install
```

To launch the instance execute the following script:
  
```
./instance/build/bin/start-kafka-metrics-instance.sh <CONF_DIR> <LOG_DIR>
```

An example local config is provided unders `./instance/build/conf`. To stop the instance:

```
./instance/build/bin/stop-kafka-metrics-instance.sh <CONF_DIR>
```


<a name="usage-loader">
## InfluxDB Loader Usage
</a>

InfluxDB Loader is a Java application which writes measurements into InfluxDB backend which can be configured
to scan the measurements from any number of JMX ports oand Kafka metrics topics.  
In versions 0.9.+, the topic input functionality is replaced by the Metrics Connect module which utilizes Kafka Connect 
framework. To build an executable jar, run the following command:

```
./gradlew :influxdb-loader:build
```

Once built, the instance can be launched with `./influxdb-loader/build/scripts/influxdb-loader` by passing it 
path to properties file containing the following configuration:
    - [InfluxDB Configuration](#configuration-influxdb) (required)
    - [JMX Scanner Configuration](#configuration-scanner) (at least one scanner or consumer is required)
    - [Metrics Consumer Configuration](#configuration-consumer) (at least on scanner or consumer is required)

There is a few example config files under `influxdb-loader/conf` which explain how JMX scanners can be added.
If you have a Kafka Broker running locally which has a JMX Server listening on port 19092 and a bundled instance of 
InfluxDB and Grafana running locally, you can use the following script and config file to collect the broker metrics:

```
./influxdb-loader/build/scripts/influxdb-loader influxdb-loader/conf/local-jmx.properties
```

<a name="usage-connect">
## Metrics Connect Usage
</a>

This module builds on Kafka Connect framework. The connector is jar that needs to be first built: 

```
./gradlew :metrics-connect:build
```

The command above generates a jar that needs to be in the classpath of Kafka Connect which can be achieved
by copying the jar into `libs` directory of the kafka installation:

```
cp ./metrics-connect/build/lib/metrics-connect-*.jar $KAFKA_HOME/libs
```

Now you can launch connect instance with the following example configurations:

```
"$KAFKA_HOME/bin/connect-standalone.sh" "metrics-connect.properties" "influxdb-sink.properties" "hdfs-sink.properties"
```

First, `metrics-connect.properties` is the connect worker configuration which doesn't specify any connectors
but says that all connectors will use MeasurementConverter to deserialize measurement objects.

```
bootstrap.servers=localhost:9092
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=io.amient.kafka.metrics.MeasurementConverter
...
```

The second configuration file is a sink connector that loads the measurements to InfluxDB, for example:

```
name=metrics-influxdb-sink
connector.class=io.amient.kafka.metrics.InfluxDbSinkConnector
topics=metric
...
```

The thrid configuration file is a sink connector that loads the measurements to hdfs, for example as parquet files:

```
name=metrics-hdfs-sink
topics=metrics
connector.class=io.confluent.connect.hdfs.HdfsSinkConnector
format.class=io.confluent.connect.hdfs.parquet.ParquetFormat
partitioner.class=io.confluent.connect.hdfs.partitioner.TimeBasedPartitioner
path.format='d'=YYYY'-'MM'-'dd/
partition.duration.ms=86400000
locale=en
timezone=Etc/GMT+1
...
```

<a name="metrics-agent">
## Metrics Agent Usage
</a>

The purpose of the agent is to move expensive metrics collection like JMX polling closer to the application and publish
these into the kafka metrics topic. The JMX scanners can be configured in the same way as with InfluxDB Loader except
 the InfluxDB backend connection is replaced with kafka metrics producer which publishes the measurements into a kafka
 topic. It is also a Java application and the executable jar can be built with the following command:  

```
./gradlew :metrics-agent:build
```

To run the agent instance, a configuration file is required, which should contain the following sections:
     - [JMX Scanner Configuration](#configuration-scanner)
     - [Metrics Producer Configuration](#configuration-producer)

```
./metrics-agent/build/scripts/kafka-metrics-agent <CONFIG-PROPERTIES-FILE>
```

<a name="usage-reporter">
## Topic Reporter Usage
</a>

The Topic Reporter provides a different way of collecting metrics from Kafka Brokers, Producers, Consumers and Samza
 processors - each of these expose configuration options for plugging a reporter directly into their runtime and the 
 class `io.amient.kafka.metrics.TopicReporter` can be used in either of them. It translates the metrics to kafka metrics
 measurements and publishes them into a topic. 
 
This reporter publishes all the metrics to configured, most often local kafka topic `metrics`. Due to different stage of 
maturity of various kafka components, watch out for subtle differences when adding TopicReporter class. To be able to 
use the reporter as plug-in for kafka brokers and tools you need to put the packaged jar in their classpath, which in 
kafka broker means putting it in the kafka /libs directory:

```
./gradlew install
cp stream-reporter/lib/stream-reporter-*.jar $KAFKA_HOME/libs/
```

The reporter only requires one set of configuration properties:
    - [Metrics Producer Configuration](#configuration-producer)


<a name="usage-reporter-kafka-old">
### Usage in Kafka Broker, Kafka Prism, Kafka Producer (pre 0.8.2), Kafka Consumer (pre 0.9)
</a>


add following properties to the configuration for the component  

```
kafka.metrics.reporters=io.amient.kafka.metrics.TopicReporter
kafka.metrics.<CONFIGURATION-OPTIONS>...
```

<a name="usage-reporter-kafka-new">
###  Usage in Kafka NEW Producer (0.8.2+) and Consumer (0.9+) 
</a>

```
metric.reporters=io.amient.kafka.metrics.TopicReporter
kafka.metrics.<CONFIGURATION-OPTIONS>...
```

<a name="usage-reporter-dropwizard">
### Usage in any application using dropwizard metrics (formerly yammer metrics)
</a>

Like any other yammer metrics reporter, given an instance (and configuration), once started, the reporter
will produce kafka-metrics messages to a configured topic every given time interval. Scala-Maven Example:

``` pom.xml
...
<dependency>
   <groupId>io.amient.kafka.metrics</groupId>
   <artifactId>metrics-reporter</artifactId>
   <version>${kafka.version}</version>
</dependency>
...
```

... Using builder for programmatic initialization

``` 
val registry = MetricsRegistry.defaultRegistry()
val reporter = TopicReporter.forRegistry(registry)
    .setTopic("metrics") //this is also default
    .setBootstrapServers("kafka1:9092,kafka2:9092")
    .setTag("host", "my-host-xyz")
    .setTag("app", "my-app-name")
    .build()
reporter.start(10, TimeUnit.SECONDS);
```

... OR Using config properties:
 
```
val registry = MetricsRegistry.defaultRegistry()
val config = new java.util.Properties(<CONFIGURATION-OPTIONS>)
val reporter = TopicReporter.forRegistry(registry).configure(config).build()
reporter.start(10, TimeUnit.SECONDS);
```

<a name="usage-samza">
##  Usage in Samza (0.9+) 
</a>

The InfluxDB Loader and Metrics Connect use the same code which understands json messages that Samza generates 
using MetricsSnapshotSerdeFactory. So just a normal samza metrics configuration without additional code, for example: 

```
metrics.reporters=topic
metrics.reporter.topic.class=org.apache.samza.metrics.reporter.MetricsSnapshotReporterFactory
metrics.reporter.topic.stream=kafkametrics.metrics
serializers.registry.metrics.class=org.apache.samza.serializers.MetricsSnapshotSerdeFactory
systems.kafkametrics.streams.metrics.samza.msg.serde=metrics
systems.kafkametrics.samza.factory=org.apache.samza.system.kafka.KafkaSystemFactory
systems.kafkametrics.consumer.zookeeper.connect=<...>
systems.kafkametrics.producer.bootstrap.servers=<...>

```


<a name="configuration">
## Configuration
</a>

<a name="configuration-influxdb">
### InfluxDB Configuration
</a>

The following configuration is required for modules that need to write to InfluxDB backend:

parameter                                  | default                | description
-------------------------------------------|------------------------|------------------------------------------------------------------------------
**influxdb.database**                      | `metrics`              | InfluxDB Database Name where to publish the measurements 
**influxdb.url**                           | `http://localhost:8086`| URL of the InfluxDB API Instance
**influxdb.username**                      | `root`                 | Authentication username for API calls
**influxdb.password**                      | `root`                 | Authentication passord for API calls

<a name="configuration-scanner">
### JMX Scanner Configuration
</a>

The following configuration options can be used with the **InfluxDB Loader** and **MetricsAgent**:

parameter                                  | default                | description
-------------------------------------------|------------------------|------------------------------------------------------------------------------
jmx.{ID}.address                  | -                      | Address of the JMX Service Endpoint 
jmx.{ID}.query.scope              | `*:*`                  | this will be used to filer object names in the JMX Server registry, i.e. `*:*` or `kafka.*:*` or `kafka.server:type=BrokerTopicMetrics,*`
jmx.{ID}.query.interval.s         | 10                     | how frequently to query the JMX Service 
jmx.{ID}.tag.{TAG-1}              | -                      | optinal tags which will be attached to each measurement  
jmx.{ID}.tag.{TAG-2}              | -                      | ...
jmx.{ID}.tag.{TAG-n}              | -                      | ...


<a name="configuration-producer">
### Metrics Producer Configuration
</a>

The following configuration options can be used with the TopicReporter and MetricsAgent:

parameter                                  | default           | description
-------------------------------------------|-------------------|------------------------------------------------------------------------------
**kafka.metrics.topic**                    | `metrics`         | Topic name where metrics are published
**kafka.metrics.polling.interval**         | `10s`             | Poll and publish frequency of metrics, llowed interval values: 1s, 10s, 1m
**kafka.metrics.bootstrap.servers**        | *inferred*        | Coma-separated list of kafka server addresses (host:port). When used in Brokers, `localhost` is default.
*kafka.metrics.tag.<tag-name>.<tag=value>* | -                 | Fixed name-value pairs that will be used as tags in the published measurement for this instance, .e.g `kafka.metrics.tag.host.my-host-01` or `kafka.metrics.tag.dc.uk-az1`  

<a name="configuration-consumer">
### Metrics Consumer Configuration
</a>

The following configuration options can be used with the modules that use Kafka consumer to get measurements:

parameter                                  | default                | description
-------------------------------------------|------------------------|------------------------------------------------------------------------------
consumer.topic                             | `metrics`              | Topic to consumer (where measurements are published by Reporter)
consumer.numThreads                        | `1`                    | Number of consumer threads
consumer.zookeeper.connect                 | `localhost:2181`       | As per [Kafka Consumer Configuration](http://kafka.apache.org/documentation.html#consumerconfigs)
consumer.group.id                          | -                      | As per Any [Kafka Consumer Configuration](http://kafka.apache.org/documentation.html#consumerconfigs)
consumer....                               | -                      | Any other [Kafka Consumer Configuration](http://kafka.apache.org/documentation.html#consumerconfigs)


<a name="operations">
## Operations & Troubleshooting
</a>


### Inspecting the metrics topic  

Using kafka console consumer with a formatter for kafka-metrics:

```
./bin/kafka-console-consumer.sh --zookeeper localhost --topic metrics --formatter io.amient.kafka.metrics.MeasurementFormatter
```

<a name="development">
## Development
</a>

- TODO: configurable log4j.properties file location and environment var overrides for configs
- TODO: expose all configs for kafka producer (NEW) configuration properties
- TODO: more robust connection error handling, e.g. when one of the cluster is not reachable, warn once and try reconnecting quietly
- TODO: sphinx documentation using generated versions in the examples
- DESIGN: explore influxdb retention options 
- DESIGN: add Kapacitor (also written in Go) to the default metrics instance
- DESIGN: REST Metrics Agent - ideally re-using Kafka REST API but only if Schema Registry is optional - for non-jvm apps
- DESIGN: should `metrics` topic represent only per cluster metric stream, NEVER aggregate, and have aggregate have `metrics_aggregated` or something ?
   - this requires the prism feature for topic name prefix/suffix 
- DESIGN: consider writing the influxdb-loader as golang kafka consumer which would lead to a kafka-metrics instance
    - Go 1.4
    - MetricsInfluxDbPublisher (Go)
    - InfluxDB 0.9 (Go)
    - Grafana 2.4 (Go) > npm (v2.5.0) > node (v0.12.0) > grunt (v0.4.5) 


