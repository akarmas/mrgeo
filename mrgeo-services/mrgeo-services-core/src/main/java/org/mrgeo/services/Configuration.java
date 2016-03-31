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

package org.mrgeo.services;

import org.mrgeo.core.MrGeoConstants;
import org.mrgeo.core.MrGeoProperties;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 *
 */
public class Configuration
{
private static Configuration instance = null;

/**
 * Made Configuration not throw a checked exception
 * @return Configuration
 */
public static Configuration getInstance()
{
  if (instance == null)
  {
    // This reduces the concurrency to just when an instance needs creating versus every access
    synchronized(Configuration.class) {
      if ( instance == null ) instance = new Configuration();
    }
  }
  return instance;
}

private Properties properties;

/**
 * Private constructor to comply with Singleton Pattern
 */
private Configuration()
{
  properties = MrGeoProperties.getInstance();
}

public Properties getProperties()
{
  return properties;
}
}
