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

package org.mrgeo.utils;

import java.io.*;

public class ObjectUtils
{
public static byte[] encodeObject(Object obj) throws IOException
{
  ByteArrayOutputStream baos = new ByteArrayOutputStream();
  ObjectOutputStream oos = null;
  try
  {
    oos = new ObjectOutputStream(baos);
    oos.writeObject(obj);
    return baos.toByteArray();
  }
  catch (IOException e)
  {
    throw e;
  }
  finally
  {
    if (oos != null)
    {
      oos.close();
    }
    baos.close();
  }

}

public static Object decodeObject(byte[] bytes) throws IOException, ClassNotFoundException
{
  ByteArrayInputStream bais = null;
  ObjectInputStream ois = null;

  try
  {
    bais = new ByteArrayInputStream(bytes);
    ois = new ObjectInputStream(bais);
    return  ois.readObject();
  }
  finally
  {
    if (ois != null)
    {
      ois.close();
    }
    if (bais != null)
    {
      bais.close();
    }
  }

}
}
