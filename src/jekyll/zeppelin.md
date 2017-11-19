---
layout: global
displayTitle: Apache Zeppelin running with Spark on Kubernetes
title: Apache Zeppelin running with Spark on Kubernetes
description: User Documentation for Apache Zeppelin running with Spark on Kubernetes
---

This page a ongoing effort to describe how to run Apache Zeppelin with Spark on Kubernetes

> At the time being, the needed code is not integrated in the `master` branches of `apache-zeppelin` nor the `apache-spark-on-k8s/spark` repositories.
> You are welcome to already ty it out and send any feedback and question.

First things first, you have to choose the following modes in which you will run Zeppelin with Spark on Kubernetes:

+ The `Kubernetes modes`: Can be `in-cluster` (within a Pod) or `out-cluster` (from outside the Kubernetes cluster).
+ The `Spark deployment modes`: Can be `client` or `cluster`.

Only three combinations of these options are supported:

1. `in-cluster` with `spark-client` mode.
2. `in-cluster` with `spark-cluster` mode.
3. `out-cluster` with `spark-cluster` mode.

For now, to be able to test these combinations, you need to build specific branches (see hereafter) or to use third-party Helm charts or Docker images. The needed branches and related PR are listed here:

1. In-cluster client mode [see pull request #456](https://github.com/apache-spark-on-k8s/spark/pull/456)
2. Add support to run Spark interpreter on a Kubernetes cluster [see pull request #2637](https://github.com/apache/zeppelin/pull/2637)

## In-Cluster with Spark-Client

![In-Cluster with Spark-Client](/img/zeppelin_in-cluster_spark-client.png "In-Cluster with Spark-Client")

Build a new Zeppelin based on [#456 In-cluster client mode](https://github.com/apache-spark-on-k8s/spark/pull/456).

Once done, deploy that new build in a Kubernetes Pod with the following interpreter settings:

+ `spark.app.name`: Do not set any name, Zeppelin with pick one for you.
+ `spark.master`: k8s://https://kubernetes:443
+ `spark.submit.deployMode`: client
+ `spark.kubernetes.driver.pod.name`: The name of the pod where your Zeppelin instance is running.
+ Other spark.k8s properties you need to make your spark working (see [Running Spark on Kubernetes](./running-on-kubernetes.html)) such as `spark.kubernetes.initcontainer.docker.image`, `spark.kubernetes.driver.docker.image`, `spark.kubernetes.executor.docker.image`...

## In-Cluster with Spark-Cluster

![In-Cluster with Spark-Cluster](/img/zeppelin_in-cluster_spark-cluster.png "In-Cluster with Spark-Cluster")

Build a new Zepplin  based on [#2637 Spark interpreter on a Kubernetes](https://github.com/apache/zeppelin/pull/2637).

Once done, deploy that new build in a Kubernetes Pod with the following interpreter settings:

+ `spark.app.name`: Do not set any name, Zeppelin with pick one for you.
+ `spark.master`: k8s://https://kubernetes:443
+ `spark.submit.deployMode`: cluster
+ `spark.kubernetes.driver.pod.name`: Do not set this property.
+ Other spark.k8s properties you need to make your spark working (see [Running Spark on Kubernetes](./running-on-kubernetes.html)) such as `spark.kubernetes.initcontainer.docker.image`, `spark.kubernetes.driver.docker.image`, `spark.kubernetes.executor.docker.image`...

## Out-Cluster with Spark-Cluster

![Out-Cluster with Spark-Client](/img/zeppelin_out-cluster_spark-cluster.png "Out-Cluster with Spark-Client")

Build a new Spark and their associated docker images based on [#2637 Spark interpreter on a Kubernetes](https://github.com/apache/zeppelin/pull/2637).

Once done, any vanilla Apache Zeppelin deployed in a Kubernetes Pod (your can use a Helm chart for this) will work out-of-the box with the following interpreter settings:

+ `spark.app.name`: Do not set any name, Zeppelin with pick one for you.
+ `spark.master`: k8s://https://ip-address-of-the-kube-api:6443 (port may depend on your setup)
+ `spark.submit.deployMode`: cluster
+ `spark.kubernetes.driver.pod.name`: Do not set this property.
+ Other spark.k8s properties you need to make your spark working (see [Running Spark on Kubernetes](./running-on-kubernetes.html)) such as `spark.kubernetes.initcontainer.docker.image`, `spark.kubernetes.driver.docker.image`, `spark.kubernetes.executor.docker.image`...
