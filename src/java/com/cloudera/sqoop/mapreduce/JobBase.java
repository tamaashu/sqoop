/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.sqoop.mapreduce;

import java.io.File;
import java.io.IOException;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;

import org.apache.hadoop.filecache.DistributedCache;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputFormat;

import org.apache.hadoop.util.StringUtils;

import com.cloudera.sqoop.SqoopOptions;

import com.cloudera.sqoop.manager.ConnManager;
import com.cloudera.sqoop.shims.HadoopShim;
import com.cloudera.sqoop.util.ClassLoaderStack;
import com.cloudera.sqoop.util.Jars;

/**
 * Base class for configuring and running a MapReduce job.
 * Allows dependency injection, etc, for easy customization of import job types.
 */
public class JobBase {

  public static final Log LOG = LogFactory.getLog(JobBase.class.getName());

  protected SqoopOptions options;
  protected Class<? extends Mapper> mapperClass;
  protected Class<? extends InputFormat> inputFormatClass;
  protected Class<? extends OutputFormat> outputFormatClass;

  private ClassLoader prevClassLoader = null;

  public JobBase() {
    this(null);
  }

  public JobBase(final SqoopOptions opts) {
    this(opts, null, null, null);
  }

  public JobBase(final SqoopOptions opts,
      final Class<? extends Mapper> mapperClass,
      final Class<? extends InputFormat> inputFormatClass,
      final Class<? extends OutputFormat> outputFormatClass) {

    this.options = opts;
    this.mapperClass = mapperClass;
    this.inputFormatClass = inputFormatClass;
    this.outputFormatClass = outputFormatClass;
  }

  /**
   * @return the mapper class to use for the job.
   */
  protected Class<? extends Mapper> getMapperClass()
      throws ClassNotFoundException {
    return this.mapperClass;
  }

  /**
   * @return the inputformat class to use for the job.
   */
  protected Class<? extends InputFormat> getInputFormatClass()
      throws ClassNotFoundException {
    return this.inputFormatClass;
  }

  /**
   * @return the outputformat class to use for the job.
   */
  protected Class<? extends OutputFormat> getOutputFormatClass()
      throws ClassNotFoundException {
    return this.outputFormatClass;
  }

  /** Set the OutputFormat class to use for this job. */
  public void setOutputFormatClass(Class<? extends OutputFormat> cls) {
    this.outputFormatClass = cls;
  }

  /** Set the InputFormat class to use for this job. */
  public void setInputFormatClass(Class<? extends InputFormat> cls) {
    this.inputFormatClass = cls;
  }

  /** Set the Mapper class to use for this job. */
  public void setMapperClass(Class<? extends Mapper> cls) {
    this.mapperClass = cls;
  }

  /**
   * Set the SqoopOptions configuring this job.
   */
  public void setOptions(SqoopOptions opts) {
    this.options = opts;
  }

  /**
   * Put jar files required by Sqoop into the DistributedCache.
   * @param job the Job being submitted.
   * @param mgr the ConnManager to use.
   */
  protected void cacheJars(Job job, ConnManager mgr)
      throws IOException {

    Configuration conf = job.getConfiguration(); 
    FileSystem fs = FileSystem.getLocal(conf);
    Set<String> localUrls = new HashSet<String>();

    addToCache(Jars.getSqoopJarPath(), fs, localUrls);
    addToCache(Jars.getShimJarPath(), fs, localUrls);
    addToCache(Jars.getDriverClassJar(mgr), fs, localUrls);

    // Add anything in $SQOOP_HOME/lib, if this is set.
    String sqoopHome = System.getenv("SQOOP_HOME");
    if (null != sqoopHome) {
      File sqoopHomeFile = new File(sqoopHome);
      File sqoopLibFile = new File(sqoopHomeFile, "lib");
      if (sqoopLibFile.exists()) {
        addDirToCache(sqoopLibFile, fs, localUrls);
      }
    } else {
      LOG.warn("SQOOP_HOME is unset. May not be able to find "
          + "all job dependencies.");
    }
    
    // If we didn't put anything in our set, then there's nothing to cache.
    if (localUrls.isEmpty()) {
      return;
    }

    // Add these to the 'tmpjars' array, which the MR JobSubmitter
    // will upload to HDFS and put in the DistributedCache libjars.
    String tmpjars = conf.get("tmpjars");
    StringBuilder sb = new StringBuilder();
    if (null != tmpjars) {
      sb.append(tmpjars);
      sb.append(",");
    }
    sb.append(StringUtils.arrayToString(localUrls.toArray(new String[0])));
    conf.set("tmpjars", sb.toString());
  }

  private void addToCache(String file, FileSystem fs, Set<String> localUrls) {
    if (null == file) {
      return;
    }

    Path p = new Path(file);
    String qualified = p.makeQualified(fs).toString();
    LOG.debug("Adding to job classpath: " + qualified);
    localUrls.add(qualified);
  }

  /**
   * Add the .jar elements of a directory to the DCache classpath,
   * nonrecursively.
   */
  private void addDirToCache(File dir, FileSystem fs, Set<String> localUrls) {
    if (null == dir) {
      return;
    }

    for (File libfile : dir.listFiles()) {
      if (libfile.exists() && !libfile.isDirectory()
          && libfile.getName().endsWith("jar")) {
        addToCache(libfile.toString(), fs, localUrls);
      }
    }
  }

  /**
   * If jars must be loaded into the local environment, do so here.
   */
  protected void loadJars(Configuration conf, String ormJarFile,
      String tableClassName) throws IOException {
    boolean isLocal = "local".equals(conf.get("mapreduce.jobtracker.address"))
        || "local".equals(conf.get("mapred.job.tracker"));
    if (isLocal) {
      // If we're using the LocalJobRunner, then instead of using the compiled
      // jar file as the job source, we're running in the current thread. Push
      // on another classloader that loads from that jar in addition to
      // everything currently on the classpath.
      this.prevClassLoader = ClassLoaderStack.addJarFile(ormJarFile,
          tableClassName);
    }
  }

  /**
   * If any classloader was invoked by loadJars, free it here.
   */
  protected void unloadJars() {
    if (null != this.prevClassLoader) {
      // unload the special classloader for this jar.
      ClassLoaderStack.setCurrentClassLoader(this.prevClassLoader);
    }
  }

  /**
   * Configure the inputformat to use for the job.
   */
  protected void configureInputFormat(Job job, String tableName,
      String tableClassName, String splitByCol)
      throws ClassNotFoundException, IOException {
    //TODO: 'splitByCol' is import-job specific; lift it out of this API.
    Class<? extends InputFormat> ifClass = getInputFormatClass();
    LOG.debug("Using InputFormat: " + ifClass);
    job.setInputFormatClass(ifClass);
  }

  /**
   * Configure the output format to use for the job.
   */
  protected void configureOutputFormat(Job job, String tableName,
      String tableClassName) throws ClassNotFoundException, IOException {
    Class<? extends OutputFormat> ofClass = getOutputFormatClass();
    LOG.debug("Using OutputFormat: " + ofClass);
    job.setOutputFormatClass(ofClass);
  }

  /**
   * Set the mapper class implementation to use in the job,
   * as well as any related configuration (e.g., map output types).
   */
  protected void configureMapper(Job job, String tableName,
      String tableClassName) throws ClassNotFoundException, IOException {
    job.setMapperClass(getMapperClass());
  }

  /**
   * Configure the number of map/reduce tasks to use in the job.
   */
  protected int configureNumTasks(Job job) throws IOException {
    int numMapTasks = options.getNumMappers();
    if (numMapTasks < 1) {
      numMapTasks = SqoopOptions.DEFAULT_NUM_MAPPERS;
      LOG.warn("Invalid mapper count; using " + numMapTasks + " mappers.");
    }

    HadoopShim.get().setJobNumMaps(job, numMapTasks);
    job.setNumReduceTasks(0);
    return numMapTasks;
  }

  /**
   * Actually run the MapReduce job.
   */
  protected boolean runJob(Job job) throws ClassNotFoundException, IOException,
      InterruptedException {
    return job.waitForCompletion(true);
  }

  /**
   * Display a notice on the log that the current MapReduce job has
   * been retired, and thus Counters are unavailable.
   * @param log the Log to display the info to.
   */
  protected void displayRetiredJobNotice(Log log) {
    log.info("The MapReduce job has already been retired. Performance");
    log.info("counters are unavailable. To get this information, ");
    log.info("you will need to enable the completed job store on ");
    log.info("the jobtracker with:");
    log.info("mapreduce.jobtracker.persist.jobstatus.active = true");
    log.info("mapreduce.jobtracker.persist.jobstatus.hours = 1");
    log.info("A jobtracker restart is required for these settings");
    log.info("to take effect.");
  }
}
