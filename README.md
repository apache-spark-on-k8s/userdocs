## spark-on-k8s-doc
Repo to host documentation site for Apache Spark on Kubernetes

### Documentation build instructions

#### Prerequisites
You must have Jekyll installed for SBT to build or deploy documentation
```bash
$ gem install jekyll
```

#### Preview
```bash
$ cd /path/to/spark-on-k8s-doc
# I find xsbt to be more reliable than sbt
$ xsbt previewSite
```
This will put the home page up on your brower so you can view a draft of the site before committing or deploying.

#### Deploy
Deploy the latest version of site content to GH pages:
```bash
$ xsbt ghpagesPushSite
```
View the user documentation for Apache Spark on Kubernetes here:

https://apache-spark-on-k8s.github.io/spark-on-k8s-doc/

#### New Content
The markdown files for this site live here:
`/path/to/spark-on-k8s-doc/src/jekyll`

The markdown for the main site page is `index.md`.
You can add new markdown files to the `src/jekyll` directory as needed.
