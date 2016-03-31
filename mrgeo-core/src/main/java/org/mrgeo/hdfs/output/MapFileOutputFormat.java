/*
 * Copyright 2009-2016 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 *
 */

package org.mrgeo.hdfs.output;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.MapFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.ReflectionUtils;

import java.io.IOException;
import java.util.Arrays;

/** 
 * An {@link org.apache.hadoop.mapreduce.OutputFormat} that writes 
 * {@link MapFile}s.
 */

public class MapFileOutputFormat 
    extends FileOutputFormat<WritableComparable<?>, Writable> {

  @Override
  public RecordWriter<WritableComparable<?>, Writable> getRecordWriter(
      TaskAttemptContext context) throws IOException {
    Configuration conf = context.getConfiguration();
    CompressionCodec codec = null;
    CompressionType compressionType = CompressionType.NONE;
    if (getCompressOutput(context)) {
      // find the kind of compression to do
      compressionType = SequenceFileOutputFormat.getOutputCompressionType(context);

      // find the right codec
      Class<?> codecClass = getOutputCompressorClass(context,
	                          DefaultCodec.class);
      codec = (CompressionCodec) ReflectionUtils.newInstance(codecClass, conf);
    }

    Path file = getDefaultWorkFile(context, "");
    FileSystem fs = file.getFileSystem(conf);
    // ignore the progress parameter, since MapFile is local
    final MapFile.Writer out =
      new MapFile.Writer(conf, fs, file.toString(),
        context.getOutputKeyClass().asSubclass(WritableComparable.class),
        context.getOutputValueClass().asSubclass(Writable.class),
        compressionType, codec, context);

    return new RecordWriter<WritableComparable<?>, Writable>() {
        @Override
        public void write(WritableComparable<?> key, Writable value)
            throws IOException {
          out.append(key, value);
        }

        @Override
        public void close(TaskAttemptContext contxt) throws IOException { 
          out.close();
        }
      };
  }

  /** Open the output generated by this format. */
  public static MapFile.Reader[] getReaders(Path dir,
      Configuration conf) throws IOException {
    FileSystem fs = dir.getFileSystem(conf);
    Path[] names = FileUtil.stat2Paths(fs.listStatus(dir));

    // sort names, so that hash partitioning works
    Arrays.sort(names);
    
    MapFile.Reader[] parts = new MapFile.Reader[names.length];
    for (int i = 0; i < names.length; i++) {
      parts[i] = new MapFile.Reader(fs, names[i].toString(), conf);
    }
    return parts;
  }
    
  /** Get an entry from output generated by this class. */
  public static <K extends WritableComparable<?>, V extends Writable>
      Writable getEntry(MapFile.Reader[] readers, 
      Partitioner<K, V> partitioner, K key, V value) throws IOException {
    int part = partitioner.getPartition(key, value, readers.length);
    return readers[part].get(key, value);
  }
}

