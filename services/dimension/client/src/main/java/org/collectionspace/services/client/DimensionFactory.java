/**
 *  This document is a part of the source code and related artifacts
 *  for CollectionSpace, an open source collections management system
 *  for museums and related institutions:

 *  http://www.collectionspace.org
 *  http://wiki.collectionspace.org

 *  Copyright 2010 University of California at Berkeley

 *  Licensed under the Educational Community License (ECL), Version 2.0.
 *  You may not use this file except in compliance with this License.

 *  You may obtain a copy of the ECL 2.0 License at

 *  https://source.collectionspace.org/collection-space/LICENSE.txt

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.collectionspace.services.client;

import javax.ws.rs.core.MediaType;

import org.collectionspace.services.client.PayloadOutputPart;
import org.collectionspace.services.client.PoxPayloadOut;
import org.collectionspace.services.dimension.DimensionsCommon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author
 */
public class DimensionFactory {

    @SuppressWarnings("unused")
	static private final Logger logger =
            LoggerFactory.getLogger(DimensionFactory.class);

    final static String SERVICE_PATH_COMPONENT = "dimensions";

    /**
     * Creates the dimension instance.
     *
     * @param commonPartName
     * @param dimension
     * @return an output payload
     */
    public static PoxPayloadOut createDimensionInstance(String commonPartName,
            DimensionsCommon dimension) {

        PoxPayloadOut multipart = new PoxPayloadOut(getServicePathComponent());
        PayloadOutputPart commonPart =
                multipart.addPart(dimension, MediaType.APPLICATION_XML_TYPE);
        commonPart.setLabel(commonPartName);

        return multipart;
    }
    
    public static String getServicePathComponent() {
        return SERVICE_PATH_COMPONENT;
    }

}
