/**
 *  This document is a part of the source code and related artifacts
 *  for CollectionSpace, an open source collections management system
 *  for museums and related institutions:

 *  http://www.collectionspace.org
 *  http://wiki.collectionspace.org

 *  Copyright 2009 University of California at Berkeley

 *  Licensed under the Educational Community License (ECL), Version 2.0.
 *  You may not use this file except in compliance with this License.

 *  You may obtain a copy of the ECL 2.0 License at

 *  https://source.collectionspace.org/collection-space/LICENSE.txt
 */
package org.collectionspace.services.nuxeo.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.collectionspace.services.client.CollectionSpaceClient;
import org.collectionspace.services.client.IQueryManager;
import org.collectionspace.services.client.PoxPayloadIn;
import org.collectionspace.services.client.PoxPayloadOut;
import org.collectionspace.services.common.api.Tools;
import org.collectionspace.services.common.context.ServiceBindingUtils;
import org.collectionspace.services.common.context.ServiceContext;
import org.collectionspace.services.common.document.DocumentException;
import org.collectionspace.services.common.document.DocumentFilter;
import org.collectionspace.services.common.document.DocumentUtils;
import org.collectionspace.services.common.query.QueryContext;
import org.collectionspace.services.common.vocabulary.RefNameServiceUtils;
import org.collectionspace.services.common.vocabulary.RefNameServiceUtils.AuthorityItemSpecifier;
import org.collectionspace.services.common.vocabulary.RefNameServiceUtils.Specifier;
import org.collectionspace.services.common.vocabulary.RefNameServiceUtils.SpecifierForm;
import org.collectionspace.services.nuxeo.client.java.NuxeoDocumentException;
import org.collectionspace.services.nuxeo.client.java.CoreSessionInterface;
import org.collectionspace.services.nuxeo.client.java.NuxeoDocumentFilter;
import org.dom4j.Document;
import org.dom4j.io.SAXReader;
import org.mortbay.log.Log;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.impl.blob.BlobWrapper;
import org.nuxeo.ecm.core.api.model.PropertyException;
import org.nuxeo.ecm.core.io.DocumentPipe;
import org.nuxeo.ecm.core.io.DocumentReader;
import org.nuxeo.ecm.core.io.DocumentWriter;
import org.nuxeo.ecm.core.io.impl.DocumentPipeImpl;
import org.nuxeo.ecm.core.io.impl.plugins.SingleDocumentReader;
import org.nuxeo.ecm.core.io.impl.plugins.XMLDocumentWriter;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.storage.StorageBlob;
import org.nuxeo.ecm.core.storage.binary.Binary;
import org.nuxeo.ecm.core.storage.sql.coremodel.SQLBlob;
import org.nuxeo.runtime.api.Framework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Various utilities related to Nuxeo API
 */
public class NuxeoUtils {

    /** The logger. */
    private static Logger logger = LoggerFactory.getLogger(NuxeoUtils.class);
    //
    // Base document type in Nuxeo is "Document"
    //
    public static final String BASE_DOCUMENT_TYPE = "Document";
    public static final String WORKSPACE_DOCUMENT_TYPE = "Workspace";
    
    public static final String Workspaces = "Workspaces";
    public static final String workspaces = "workspaces"; // to make it easier to migrate older versions of the CollectionSpace services -i.e., pre v2.0.
        
    // Regular expressions pattern for identifying valid ORDER BY clauses.
    // FIXME: Currently supports only USASCII word characters in field names.
    //private static final String ORDER_BY_CLAUSE_REGEX = "\\w+(_\\w+)?:\\w+( ASC| DESC)?(, \\w+(_\\w+)?:\\w+( ASC| DESC)?)*";    
		// Allow paths so can sort on complex fields. CSPACE-4601
    private static final String ORDER_BY_CLAUSE_REGEX = "\\w+(_\\w+)?:\\w+(/(\\*|\\w+))*( ASC| DESC)?(, \\w+(_\\w+)?:\\w+(/(\\*|\\w+))*( ASC| DESC)?)*";
	
    /* 
     * Keep this method private.  This method uses reflection to gain access to a protected field in Nuxeo's "Binary" class.  If and when we learn how
     * to locate the "file" field of a Binary instance without breaking our "contract" with this class, we should minimize
     * our use of this method.
     */
    private static File getFileOfBlob(Blob blob) {
    	File result = null;
    	
    	if (blob instanceof BlobWrapper) {
    		BlobWrapper blobWrapper = (BlobWrapper)blob;
			try {
				Field blobField;
				blobField = blobWrapper.getClass().getDeclaredField("blob");
				boolean accessibleState = blobField.isAccessible();
				if (accessibleState == false) {
					blobField.setAccessible(true);
				}
    			blob = (StorageBlob)blobField.get(blobWrapper);
    			blobField.setAccessible(accessibleState); // set it back to its original access state				
			} catch (Exception e) {
				logger.error("blob field of BlobWrapper is not accessible.", e);
			}
    	}
    	
    	if (blob instanceof StorageBlob) {
    		StorageBlob sqlBlob = (StorageBlob)blob;
    		Binary binary = sqlBlob.getBinary();
    		try {
    			Field fileField = binary.getClass().getDeclaredField("file");
    			boolean accessibleState = fileField.isAccessible();
    			if (accessibleState == false) {
    				fileField.setAccessible(true);
    			}
    			result = (File)fileField.get(binary);
    			fileField.setAccessible(accessibleState); // set it back to its original access state
    		} catch (Exception e) {
    			logger.error("Was not able to find the 'file' field", e);
    		}    		
    	}
    	
    	return result;
    }
    
    static public Thread deleteFileOfBlobAsync(Blob blob) {
    	Thread result = null;
    	
    	//
    	// Define a new thread that will try to delete the file of the blob.  We
    	// need this to happen on a separate thread because our current thread seems
    	// to still have an active handle to the file so our non-thread delete calls
    	// are failing.  The new thread will make 10 attempts, separated by 1 second, to
    	// delete the file.  If after 10 attempts, it still can't delete the file, it will
    	// log an error.
    	//
    	final File fileToDelete = getFileOfBlob(blob);
    	final String blobName = blob.getFilename();
    	Thread deleteFileThread = new Thread() {
    		@Override public void run() {
	    		boolean deleteSuccess = false;
	    		int attempts = 0;
	    		while (attempts++ < 10 && deleteSuccess != true) {
	    			deleteSuccess = deleteFile(fileToDelete);
	    			if (deleteSuccess == false) {
	    				//
	    				// We couldn't delete the file, so some other thread might still
	    				// have a handle to it.  Let's put this thread to sleep for 1 second
	    				// before trying to delete it again.
	    				//
		    			try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							logger.error(String.format("Unable to delete file '%s' of blob '%s'.",
		    					fileToDelete.getAbsoluteFile(), blobName), e);
						}
	    			}
	    		}
	    		//
	    		// Now log the result.
	    		//
	    		if (deleteSuccess) {
	    			logger.debug(String.format("Successfully deleted file '%s' of blob '%s'.",
	    					fileToDelete.getAbsoluteFile(), blobName));
	    		} else {
	    			logger.error(String.format("Unable to delete file '%s' of blob '%s'.",
	    					fileToDelete.getAbsoluteFile(), blobName));
	    		}
    		}
    	};
    	deleteFileThread.start();
    	result = deleteFileThread;
    	
    	return result;
    }
    
    static public boolean deleteFileOfBlob(Blob blob) {
    	File fileToDelete = getFileOfBlob(blob);
    	return deleteFile(fileToDelete);
    }
    
    static public boolean deleteFile(File fileToDelete) {
    	boolean result = true;
    	
    	Exception deleteException = null;
    	try {
			java.nio.file.Files.delete(fileToDelete.toPath());
			Log.debug(String.format("Deleted file '%s'.", fileToDelete.getCanonicalPath()));
		} catch (IOException e) {
			deleteException = e;
			result = false;
		}
    	
		if (result == false) {
			logger.warn("Could not delete the file at: " + fileToDelete.getAbsolutePath(),
					deleteException);
		}
    	
    	return result;
    }
    
    /*
     * This method will fail to return a facet list if non exist or if Nuxeo changes the
     * DocumentModelImpl class "facets" field to be of a different type or if they remove it altogether.
     */
    public static Set<String> getFacets(DocumentModel docModel) {
    	Set<String> result = null;
    	
    	try {
			Field field = docModel.getClass().getDeclaredField("facets");
			field.setAccessible(true);
			result = (Set<String>) field.get(docModel);
			field.setAccessible(false);
    	} catch (Exception e) {
    		logger.error("Could not remove facet from DocumentModel instance: " + docModel.getId(), e);
    	}
    	
    	return result;
    }
    
    /*
     * Remove a Nuxeo facet from a document model instance
     */
    public static boolean removeFacet(DocumentModel docModel, String facet) {
    	boolean result = false;
    	
    	Set<String> facets = getFacets(docModel);
    	if (facets != null && facets.contains(facet)) {
    		facets.remove(facet);
    		result = true;
    	}
		
		return result;
    }
    
    /*
     * Adds a Nuxeo facet to a document model instance
     */
    public static boolean addFacet(DocumentModel docModel, String facet) {
    	boolean result = false;
    	
    	Set<String> facets = getFacets(docModel);
    	if (facets != null && !facets.contains(facet)) {
    		facets.add(facet);
    		result = true;
    	}
		
		return result;
    }    

    public static void exportDocModel(DocumentModel src) {
    	DocumentReader reader = null;
    	DocumentWriter writer = null;

    	CoreSession repoSession = src.getCoreSession();
    	try { 
    	  reader = new SingleDocumentReader(repoSession, src);
    	        
    	  // inline all blobs
//    	  ((DocumentTreeReader)reader).setInlineBlobs(true);
    	  File tmpFile = new File("/tmp/nuxeo_export-" +
    			  System.currentTimeMillis() + ".zip");
    	  System.out.println(tmpFile.getAbsolutePath());
    	  writer = new XMLDocumentWriter(tmpFile);
    	        
    	  // creating a pipe
    	  DocumentPipe pipe = new DocumentPipeImpl();
    	        
    	  // optionally adding a transformer
//    	  pipe.addTransformer(new MyTransformer());
    	  pipe.setReader(reader);
    	  pipe.setWriter(writer); pipe.run();
    	        
    	} catch (Exception x) {
    		x.printStackTrace();
    	} finally { 
    	  if (reader != null) {
    	    reader.close(); 
    	  } 
    	  if (writer != null) { 
    	    writer.close();
    	  }
    	}    	
    }
    /**
     * getDocument retrieve org.dom4j.Document from Nuxeo DocumentModel
     * @param repoSession
     * @param nuxeoDoc
     * @return
     * @throws DocumentException
     */
    public static Document getDocument(CoreSessionInterface repoSession, DocumentModel nuxeoDoc)
            throws DocumentException {
        Document doc = null;
        DocumentWriter writer = null;
        DocumentReader reader = null;
        ByteArrayOutputStream baos = null;
        ByteArrayInputStream bais = null;
        try {
            baos = new ByteArrayOutputStream();
            //nuxeo io.impl begin
            reader = new SingleDocumentReader(repoSession.getCoreSession(), nuxeoDoc);
            writer = new XMLDocumentWriter(baos);
            DocumentPipe pipe = new DocumentPipeImpl();
            //nuxeo io.impl end
            pipe.setReader(reader);
            pipe.setWriter(writer);
            pipe.run();
            bais = new ByteArrayInputStream(baos.toByteArray());
            SAXReader saxReader = new SAXReader();
            doc = saxReader.read(bais);
        } catch (ClientException ce) {
        	throw new NuxeoDocumentException(ce);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Caught exception while processing document ", e);
            }
            throw new DocumentException(e);
        } finally {
            if (reader != null) {
                reader.close();
            }
            if (writer != null) {
                writer.close();
            }
            try {
                if (bais != null) {
                    bais.close();
                }
                if (baos != null) {
                    baos.close();
                }
            } catch (IOException ioe) {
                String msg = "Failed to close io streams";
                logger.error(msg + " {}", ioe);
                throw new DocumentException(ioe);
            }
        }
        return doc;
    }

    /**
     * Gets the document.
     *
     * @param repoSession the repo session
     * @param csid the csid
     *
     * @return the document
     *
     * @throws DocumentException the document exception
     */
    public static Document getDocument(CoreSessionInterface repoSession, String csid)
            throws DocumentException {
        Document result = null;

        DocumentModel docModel = getDocumentModel(repoSession, csid);
        result = getDocument(repoSession, docModel);

        return result;
    }

    /**
     * Gets the workspace model.
     *
     * @param repoSession the repo session
     * @param workspaceName the workspace name
     *
     * @return the workspace model
     *
     * @throws DocumentException the document exception
     * @throws IOException Signals that an I/O exception has occurred.
     * @throws ClientException the client exception
     */
    public static DocumentModel getWorkspaceModel(
    		CoreSessionInterface repoSession, String workspaceName)
            throws DocumentException, IOException, ClientException {
        DocumentModel result = null;
        //FIXME: commented out as this does not work without tenant qualification
        String workspaceUUID = null;
//		String workspaceUUID = ServiceMain.getInstance().getWorkspaceId(
//				workspaceName);
        DocumentRef workspaceRef = new IdRef(workspaceUUID);
        result = repoSession.getDocument(workspaceRef);

        return result;
    }

    /**
     * Gets the document model corresponding to the Nuxeo ID.
     * 
     * WARNING: Service should *rarely* if ever use this method.  It bypasses our tenant and
     * security filters.
     *
     * @param repoSession the repo session
     * @param csid the csid
     *
     * @return the document model
     *
     * @throws DocumentException the document exception
     */
    public static DocumentModel getDocumentModel(
    		CoreSessionInterface repoSession, String nuxeoId)
            throws DocumentException {
        DocumentModel result = null;

        try {
            DocumentRef documentRef = new IdRef(nuxeoId);
            result = repoSession.getDocument(documentRef);
        } catch (ClientException e) {
            throw new NuxeoDocumentException(e);
        }

        return result;
    }
    
    static public String getByNameWhereClause(String csid) {
    	String result = null;
    	
    	if (csid != null) {
    		result = "ecm:name = " + "\'" + csid + "\'";
    	}
    	
    	return result;
    }
        
    /**
     * Append a WHERE clause to the NXQL query.
     *
     * @param query         The NXQL query to which the WHERE clause will be appended.
     * @param queryContext  The query context, which provides the WHERE clause to append.
     */
    static private final void appendNXQLWhere(StringBuilder query, QueryContext queryContext) {

    	// Filter documents that are proxies (speeds up the query) and filter checked in versions
    	// for services that are using versioning.
    	// TODO This should really be handled as a default query param so it can be overridden, 
    	// allowing clients to find versions, just as they can find soft-deleted items.
    	final String PROXY_AND_VERSION_FILTER = 
    			  IQueryManager.SEARCH_QUALIFIER_AND + IQueryManager.NUXEO_IS_PROXY_FILTER
    			+ IQueryManager.SEARCH_QUALIFIER_AND + IQueryManager.NUXEO_IS_VERSION_FILTER;
        //
        // Restrict search to a specific Nuxeo domain
        // TODO This is a slow method for tenant-filter
        // We should make this a property that is indexed.
        //
//        query.append(" WHERE ecm:path STARTSWITH '/" + queryContext.domain + "'");

        //
        // Restrict search to the current tenant ID.  Is the domain path filter (above) still needed?
        //
        query.append(/*IQueryManager.SEARCH_QUALIFIER_AND +*/ " WHERE " + CollectionSpaceClient.COLLECTIONSPACE_CORE_SCHEMA + ":"
                + CollectionSpaceClient.COLLECTIONSPACE_CORE_TENANTID
                + " = " + "'" + queryContext.getTenantId() + "'");
        //
        // Finally, append the incoming where clause
        //
        String whereClause = queryContext.getWhereClause();
        if (whereClause != null && ! whereClause.trim().isEmpty()) {
            // Due to an apparent bug/issue in how Nuxeo translates the NXQL query string
            // into SQL, we need to parenthesize our 'where' clause
            query.append(IQueryManager.SEARCH_QUALIFIER_AND + "(" + whereClause + ")");
        }
        //
        // See "Special NXQL Properties" at http://doc.nuxeo.com/display/NXDOC/NXQL
        //
        query.append(PROXY_AND_VERSION_FILTER);
    }

    /**
     * Append an ORDER BY clause to the NXQL query.
     *
     * @param query         the NXQL query to which the ORDER BY clause will be appended.
     * @param queryContext  the query context, which provides the ORDER BY clause to append.
     *
     * @throws DocumentException  if the supplied value of the orderBy clause is not valid.
     *
     */
    static private final void appendNXQLOrderBy(StringBuilder query, String orderByClause, String orderByPrefix)
            throws Exception {
        if (orderByClause != null && ! orderByClause.trim().isEmpty()) {
            if (isValidOrderByClause(orderByClause)) {
                query.append(" ORDER BY ");
                if (orderByPrefix != null) {
                	query.append(orderByPrefix);
                }
                query.append(orderByClause);
            } else {
                throw new DocumentException("Invalid format in sort request '" + orderByClause
                        + "': must be schema_name:fieldName followed by optional sort order (' ASC' or ' DESC').");
            }
        }
    }

    /**
     * Append an ORDER BY clause to the NXQL query.
     *
     * @param query         the NXQL query to which the ORDER BY clause will be appended.
     * @param queryContext  the query context, which provides the ORDER BY clause to append.
     *
     * @throws DocumentException  if the supplied value of the orderBy clause is not valid.
     *
     */
    static private final void appendNXQLOrderBy(StringBuilder query, QueryContext queryContext)
            throws Exception {
        String orderByClause = queryContext.getOrderByClause();
        appendNXQLOrderBy(query, orderByClause, null);
    }
        
    static public final void appendCMISOrderBy(StringBuilder query, QueryContext queryContext)
            throws Exception {
        String orderByClause = queryContext.getOrderByClause();
        appendNXQLOrderBy(query, orderByClause, IQueryManager.CMIS_TARGET_PREFIX + ".");
    }

    /**
     * Identifies whether the ORDER BY clause is valid.
     *
     * @param orderByClause the ORDER BY clause.
     *
     * @return              true if the ORDER BY clause is valid;
     *                      false if it is not.
     */
    static private final boolean isValidOrderByClause(String orderByClause) {
        boolean isValidClause = false;
        try {
            Pattern orderByPattern = Pattern.compile(ORDER_BY_CLAUSE_REGEX);
            Matcher orderByMatcher = orderByPattern.matcher(orderByClause);
            if (orderByMatcher.matches()) {
                isValidClause = true;
            }
        } catch (PatternSyntaxException pe) {
            logger.warn("ORDER BY clause regex pattern '" + ORDER_BY_CLAUSE_REGEX
                    + "' could not be compiled: " + pe.getMessage());
            // If reached, method will return a value of false.
        }
        return isValidClause;
    }
    

    /**
     * Builds an NXQL SELECT query for a single document type.
     *
     * @param queryContext The query context
     * @return an NXQL query
     * @throws Exception if supplied values in the query are invalid.
     */
    static public final String buildNXQLQuery(ServiceContext<PoxPayloadIn, PoxPayloadOut> ctx, QueryContext queryContext) throws Exception {
        StringBuilder query = new StringBuilder(queryContext.getSelectClause());
        // Since we have a tenant qualification in the WHERE clause, we do not need 
        // tenant-specific doc types
        // query.append(NuxeoUtils.getTenantQualifiedDocType(queryContext)); // Nuxeo doctype must be tenant qualified.
        query.append(queryContext.getDocType());
        appendNXQLWhere(query, queryContext);
        appendNXQLOrderBy(query, queryContext);
        return query.toString();
    }
    
    static public final String buildCMISQuery(ServiceContext<PoxPayloadIn, PoxPayloadOut> ctx, QueryContext queryContext) throws Exception {
        StringBuilder query = new StringBuilder("SELECT * FROM ");

        /*
         * This is a place holder for CMIS query creation -see buildNXQLQuery as a reference
         */

        return query.toString();
    }
    
    static public final String buildWorkflowNotDeletedWhereClause() {
    	return "ecm:currentLifeCycleState <> 'deleted'";
    }
    
    
    /**
     * Builds an NXQL SELECT query across multiple document types.
     *
     * @param docTypes     a list of document types to be queried
     * @param queryContext the query context
     * @return an NXQL query
     */
    static public final String buildNXQLQuery(List<String> docTypes, QueryContext queryContext) throws Exception {
        StringBuilder query = new StringBuilder(queryContext.getSelectClause()); 
        boolean fFirst = true;
        for (String docType : docTypes) {
            if (fFirst) {
                fFirst = false;
            } else {
                query.append(",");
            }
            // Since we have a tenant qualification in the WHERE clause, we do not need 
            // tenant-specific doc types
            // String tqDocType = getTenantQualifiedDocType(queryContext, docType);
            // query.append(tqDocType); // Nuxeo doctype must be tenant qualified.
            query.append(docType);
        }
        appendNXQLWhere(query, queryContext);
        if (Tools.notBlank(queryContext.getOrderByClause())) {
            appendNXQLOrderBy(query, queryContext.getOrderByClause(), null);
        } else {
            // Across a set of mixed DocTypes, updatedAt is the most sensible default ordering
            appendNXQLOrderBy(query, DocumentFilter.ORDER_BY_LAST_UPDATED, null);
        }
        // FIXME add 'order by' clause here, if appropriate
        return query.toString();
    }
    
    static public DocumentModel getDocFromCsid(
    		ServiceContext<PoxPayloadIn, PoxPayloadOut> ctx,
    		CoreSessionInterface repoSession,
    		String csid) throws Exception {
	    DocumentModel result = null;
	
	    DocumentModelList docModelList = null;
        //
        // Set of query context using the current service context, but change the document type
        // to be the base Nuxeo document type so we can look for the document across service workspaces
        //
        QueryContext queryContext = new QueryContext(ctx, getByNameWhereClause(csid));
        queryContext.setDocType(NuxeoUtils.BASE_DOCUMENT_TYPE);
        //
        // Since we're doing a query, we get back a list so we need to make sure there is only
        // a single result since CSID values are supposed to be unique.
        String query = buildNXQLQuery(ctx, queryContext);
        docModelList = repoSession.query(query);
        long resultSize = docModelList.totalSize();
        if (resultSize == 1) {
        	result = docModelList.get(0);
        } else if (resultSize > 1) {
        	throw new DocumentException("Found more than 1 document with CSID = " + csid);
        }

        return result;
    }
    
    static public DocumentModel getDocFromSpecifier(
    		ServiceContext<PoxPayloadIn, PoxPayloadOut> ctx,
    		CoreSessionInterface repoSession,
    		String schemaName,
    		AuthorityItemSpecifier specifier) throws Exception {
	    DocumentModel result = null;
	
        if (specifier.getItemSpecifier().form == SpecifierForm.CSID) {
            result = getDocFromCsid(ctx, repoSession, specifier.getItemSpecifier().value);
        } else {
        	//
        	// The parent part of the specifier must be a CSID form.
        	//
        	if (specifier.getParentSpecifier().form != SpecifierForm.CSID) {
        		throw new DocumentException(String.format("Specifier for item parent must be of CSID form but was '%s'",
        				specifier.getParentSpecifier().value));
        	}
        	//
        	// Build the query to get the authority item.  Parent value must be a CSID.
        	//
            String whereClause = RefNameServiceUtils.buildWhereForAuthItemByName(schemaName,
            		specifier.getItemSpecifier().value, specifier.getParentSpecifier().value);  // parent value must be a CSID
	        QueryContext queryContext = new QueryContext(ctx, whereClause);
	        //
	        // Set of query context using the current service context, but change the document type
	        // to be the base Nuxeo document type so we can look for the document across service workspaces
	        //
	        queryContext.setDocType(NuxeoUtils.BASE_DOCUMENT_TYPE);
	    
		    DocumentModelList docModelList = null;
	        //
	        // Since we're doing a query, we get back a list so we need to make sure there is only
	        // a single result since CSID values are supposed to be unique.
	        String query = buildNXQLQuery(ctx, queryContext);
	        docModelList = repoSession.query(query);
	        long resultSize = docModelList.totalSize();
	        if (resultSize == 1) {
	        	result = docModelList.get(0);
	        } else if (resultSize > 1) {
	        	throw new DocumentException("Found more than 1 document with specifier ID = " + specifier.getItemSpecifier().value);
	        }
        }

        return result;
    } 
    
    static public DocumentModel getDocFromSpecifier(
    		ServiceContext<PoxPayloadIn, PoxPayloadOut> ctx,
    		CoreSessionInterface repoSession,
    		String schemaName,
    		Specifier specifier) throws Exception {
	    DocumentModel result = null;
	
        if (specifier.form == SpecifierForm.CSID) {
            result = getDocFromCsid(ctx, repoSession, specifier.value);
        } else {
            String whereClause = RefNameServiceUtils.buildWhereForAuthByName(schemaName, specifier.value);
	        QueryContext queryContext = new QueryContext(ctx, whereClause);
	        //
	        // Set of query context using the current service context, but change the document type
	        // to be the base Nuxeo document type so we can look for the document across service workspaces
	        //
	        queryContext.setDocType(NuxeoUtils.BASE_DOCUMENT_TYPE);
	    
		    DocumentModelList docModelList = null;
	        //
	        // Since we're doing a query, we get back a list so we need to make sure there is only
	        // a single result since CSID values are supposed to be unique.
	        String query = buildNXQLQuery(ctx, queryContext);
	        docModelList = repoSession.query(query);
	        long resultSize = docModelList.totalSize();
	        if (resultSize == 1) {
	        	result = docModelList.get(0);
	        } else if (resultSize > 1) {
	        	throw new DocumentException("Found more than 1 document with CSID = " + specifier.value);
	        }
        }

        return result;
    }     

    /*
    public static void printDocumentModel(DocumentModel docModel) throws Exception {
        String[] schemas = docModel.getDeclaredSchemas();
        for (int i = 0; schemas != null && i < schemas.length; i++) {
            logger.debug("Schema-" + i + "=" + schemas[i]);
        }

        DocumentPart[] parts = docModel.getParts();
        Map<String, Serializable> propertyValues = null;
        for (int i = 0; parts != null && i < parts.length; i++) {
            logger.debug("Part-" + i + " name =" + parts[i].getName());
            logger.debug("Part-" + i + " path =" + parts[i].getPath());
            logger.debug("Part-" + i + " schema =" + parts[i].getSchema().getName());
            propertyValues = parts[i].exportValues();
        }
    }
    */

    /**
     * createPathRef creates a PathRef for given service context using given id
     * @param ctx
     * @param id
     * @return PathRef
     */
    public static DocumentRef createPathRef(ServiceContext<PoxPayloadIn, PoxPayloadOut> ctx, String id) {
        return new PathRef("/" + ctx.getRepositoryDomainStorageName() +
                "/" + Workspaces +
                "/" + ctx.getRepositoryWorkspaceName() +
                "/" + id);
    }

    /*
     * We're using the "name" field of Nuxeo's DocumentModel to store
     * the CSID.
     */
    public static String getCsid(DocumentModel docModel) {
    	return docModel.getName();
    }
    
    /**
     * extractId extracts id from given path string
     * @param pathString
     * @return
     */
    @Deprecated
    public static String xextractId(String pathString) {
        if (pathString == null) {
            throw new IllegalArgumentException("empty pathString");
        }
        String id = null;
        StringTokenizer stz = new StringTokenizer(pathString, "/");
        int tokens = stz.countTokens();
        for (int i = 0; i < tokens - 1; i++) {
            stz.nextToken();
        }
        id = stz.nextToken(); //last token is id
        return id;
    }
    
    /**
     * Return the string literal in a form ready to embed in an NXQL statement.
     *
     * @param s
     * @return
     */
    public static String prepareStringLiteral(String s) {
        return "'" + s.replaceAll("'", "\\\\'") + "'";
    }
    
    public static boolean documentExists(CoreSessionInterface repoSession,
    		String csid) throws ClientException {
		boolean result = false;
		
		String statement = String.format(
				"SELECT ecm:uuid FROM Document WHERE ecm:name = %s", prepareStringLiteral(csid));
		final int RETURN_ONE_ROW = 1; // Return no more than 1 row
		DocumentModelList  res = repoSession.query(statement, RETURN_ONE_ROW);

		result = res.iterator().hasNext();
		if (result = false) {
			if (logger.isDebugEnabled() == true) {
				logger.debug("Existance check failed for document with CSID = " + csid);
			}
		} else {
			//String uuid = (String) res.next().get(NXQL.ECM_UUID);
		}
			
		return result;
    }
    
    public static String getTenantQualifiedDocType(String tenantId, String docType) throws Exception {
    	String result = docType;
    	
		String tenantQualifiedDocType = ServiceBindingUtils.getTenantQualifiedDocType(tenantId, docType);

		if (docTypeExists(tenantQualifiedDocType) == true) {
			result = tenantQualifiedDocType;
		}
		
    	return result;
    }

    
    public static String getTenantQualifiedDocType(ServiceContext<PoxPayloadIn, PoxPayloadOut> ctx, String docType) throws Exception {
    	String result = docType;
    	
		String tenantQualifiedDocType = ctx.getTenantQualifiedDoctype(docType);
		if (docTypeExists(tenantQualifiedDocType) == true) {
			result = tenantQualifiedDocType;
		}
		
    	return result;
    }

    public static String getTenantQualifiedDocType(ServiceContext<PoxPayloadIn, PoxPayloadOut> ctx) throws NuxeoDocumentException {
    	String result = null;

    	try {
			String docType = ctx.getDocumentType();
			result = getTenantQualifiedDocType(ctx, docType);
    	} catch (Exception e) {
    		throw new NuxeoDocumentException(e);
    	}
    	
    	return result;
    }
    
    public static String getTenantQualifiedDocType(QueryContext queryCtx, String docType) throws Exception {
    	String result = docType;
    	
    	try {
	    	String tenantQualifiedDocType = queryCtx.getTenantQualifiedDoctype();
			if (docTypeExists(tenantQualifiedDocType) == true) {
				result = tenantQualifiedDocType;
			}
    	} catch (ClientException ce) {
    		throw new NuxeoDocumentException(ce);
    	}
		
    	return result;
    }
    
    public static String getTenantQualifiedDocType(QueryContext queryCtx) throws Exception {		
    	return getTenantQualifiedDocType(queryCtx, queryCtx.getDocType());
    }
    
    static private boolean docTypeExists(String docType) throws Exception {
    	boolean result = false;
    	
        SchemaManager schemaManager = null;
    	try {
			schemaManager = Framework.getService(org.nuxeo.ecm.core.schema.SchemaManager.class);
    	} catch (ClientException ce) {
    		throw new NuxeoDocumentException(ce);
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			logger.error("Could not get Nuxeo SchemaManager instance.", e1);
			throw e1;
		}
    	
		Set<String> docTypes = schemaManager.getDocumentTypeNamesExtending(docType);
		if (docTypes != null && docTypes.contains(docType)) {
			result = true;
		}
		
    	return result;
    }
    
    /*
     * Returns the property value for an instance of a DocumentModel.  If there is no value for the
     * property, we'll return null.
     * 
     * Beginning in Nuxeo 6, if a DocumentModel has no value for the property, we get a NPE when calling
     * the DocumentModel.getPropertyValue method.  This method catches that NPE and instead returns null.
     */
    public static Object getProperyValue(DocumentModel docModel,
    		String propertyName) throws ClientException, PropertyException {
    	Object result = null;
    	
    	try {
    		result = docModel.getPropertyValue(propertyName);
    	} catch (NullPointerException npe) {
			logger.warn(String.format("Could not get a value for the property '%s' in Nuxeo document with CSID '%s'.",
					propertyName, docModel.getName()));
    	}
    	
    	return result;
    }
    
    /**
     * Gets XPath value from schema. Note that only "/" and "[n]" are
     * supported for xpath. Can omit grouping elements for repeating complex types, 
     * e.g., "fieldList/[0]" can be used as shorthand for "fieldList/field[0]" and
     * "fieldGroupList/[0]/field" can be used as shorthand for "fieldGroupList/fieldGroup[0]/field".
     * If there are no entries for a list of scalars or for a list of complex types, 
     * a 0 index expression (e.g., "fieldGroupList/[0]/field") will safely return an empty
     * string. A non-zero index will throw an IndexOutOfBoundsException if there are not
     * that many elements in the list. 
     * N.B.: This does not follow the XPath spec - indices are 0-based, not 1-based.
     *
     * @param docModel The document model to get info from
     * @param schema The name of the schema (part)
     * @param xpath The XPath expression (without schema prefix)
     * @return value the indicated property value as a String
     */
	public static Object getXPathValue(DocumentModel docModel,
			String schema, String xpath) throws NuxeoDocumentException {
		Object result = null;

		String targetCSID = null;
		xpath = schema + ":" + xpath;
		try {
			Object value = docModel.getPropertyValue(xpath);
			targetCSID = docModel.getName();
			String returnVal = null;
			if (value == null) {
				// Nothing to do - leave returnVal null
			} else {
				returnVal = DocumentUtils.propertyValueAsString(value, docModel, xpath);
			}
			result = returnVal;
		} catch (ClientException ce) {
			String msg = "Unknown Nuxeo client exception.";
			if (ce instanceof PropertyException) {
				msg = String.format("Problem retrieving property for xpath { %s } with CSID = %s.", xpath, targetCSID);
			}
			throw new NuxeoDocumentException(msg, ce);  // We need to wrap this exception in order to retry failed requests caused by network errors
		} catch (ClassCastException cce) {
			throw new ClassCastException("Problem retrieving property {" + xpath
					+ "} as String. Not a String property?"
					+ cce.getLocalizedMessage());
		} catch (IndexOutOfBoundsException ioobe) {
			// Nuxeo seems to handle foo/[0]/bar when it is missing,
			// but not foo/bar[0] (for repeating scalars).
			if (xpath.endsWith("[0]")) { // gracefully handle missing elements
				result = "";
			} else {
				String msg = ioobe.getMessage();
				if (msg != null && msg.equals("Index: 0, Size: 0")) {
					// Some other variant on a missing sub-field; quietly
					// absorb.
					result = "";
				}
			}
			// Otherwise, e.g., for true OOB indices, propagate the exception.
			if (result == null) {
				throw new IndexOutOfBoundsException("Problem retrieving property {" + xpath
						+ "}:" + ioobe.getLocalizedMessage());
			}
		} catch (NullPointerException npe) {
			logger.trace(String.format("Null value found for property '%s' for document with ID %s",
					xpath, docModel.getName()), npe);
		}

		return result;
	}
    
    static public String getPrimaryXPathPropertyName(String schema, String complexPropertyName, String fieldName) {
        if (Tools.isBlank(schema)) {
            return complexPropertyName + "/[0]/" + fieldName;
        } else {
    	    return schema + ":" + complexPropertyName + "/[0]/" + fieldName;
        }
    }
    
    static public String getPrimaryElPathPropertyName(String schema, String complexPropertyName, String fieldName) {
        if (Tools.isBlank(schema)) {
            return complexPropertyName + "/0/" + fieldName;
        } else {
    	    return schema + ":" + complexPropertyName + "/0/" + fieldName;
        }
    }
    
    static public String getMultiElPathPropertyName(String schema, String complexPropertyName, String fieldName) {
        if (Tools.isBlank(schema)) {
            return complexPropertyName + "/*/" + fieldName;
        } else {
    	    return schema + ":" + complexPropertyName + "/*/" + fieldName;
        }
    }

    
}
