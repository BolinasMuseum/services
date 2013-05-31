package org.collectionspace.services.nuxeo.extension.botgarden;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.collectionspace.services.batch.nuxeo.UpdateAccessCodeBatchJob;
import org.collectionspace.services.client.workflow.WorkflowClient;
import org.collectionspace.services.collectionobject.nuxeo.CollectionObjectConstants;
import org.collectionspace.services.common.ResourceMap;
import org.collectionspace.services.common.invocable.InvocationResults;
import org.collectionspace.services.common.relation.nuxeo.RelationConstants;
import org.collectionspace.services.taxonomy.nuxeo.TaxonConstants;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.event.DocumentEventTypes;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;

/**
 * A listener that updates the access code on taxon records when collectionobjects
 * or taxon records are created or modified.
 * 
 * @see org.collectionspace.services.batch.nuxeoUpdateAccessCodeBatchJob
 * @author ray
 *
 */
public class UpdateAccessCodeListener implements EventListener {
	final Log logger = LogFactory.getLog(UpdateAccessCodeListener.class);

	public static final String PREVIOUS_DEAD_FLAG_PROPERTY_NAME = "UpdateAccessCodeListener.previousDeadFlag";
	public static final String PREVIOUS_TAXON_NAMES_PROPERTY_NAME = "UpdateAccessCodeListener.previousTaxonNames";
	public static final String PREVIOUS_ACCESS_CODE_PROPERTY_NAME = "UpdateAccessCodeListener.previousAccessCode";
	public static final String DELETED_RELATION_PARENT_CSID_PROPERTY_NAME = "UpdateAccessCodeListener.deletedRelationParentCsid";
	
	private static final String[] TAXON_PATH_ELEMENTS = CollectionObjectConstants.TAXON_FIELD_NAME.split("/");
	private static final String TAXONOMIC_IDENT_GROUP_LIST_FIELD_NAME = TAXON_PATH_ELEMENTS[0];
	private static final String TAXON_FIELD_NAME = TAXON_PATH_ELEMENTS[2];

	public void handleEvent(Event event) throws ClientException {
		EventContext ec = event.getContext();
		
		if (ec instanceof DocumentEventContext) {
			DocumentEventContext context = (DocumentEventContext) ec;
			DocumentModel doc = context.getSourceDocument();

			logger.debug("docType=" + doc.getType());

			if (doc.getType().startsWith(CollectionObjectConstants.NUXEO_DOCTYPE) &&
					!doc.isVersion() && 
					!doc.isProxy() && 
					!doc.getCurrentLifeCycleState().equals(WorkflowClient.WORKFLOWSTATE_DELETED)) {
				
				if (event.getName().equals(DocumentEventTypes.BEFORE_DOC_UPDATE)) {
					// Stash the previous dead flag and taxonomic ident values, so they can be retrieved in the documentModified handler.
					
					DocumentModel previousDoc = (DocumentModel) context.getProperty(CoreEventConstants.PREVIOUS_DOCUMENT_MODEL);

					String previousDeadFlag = (String) previousDoc.getProperty(CollectionObjectConstants.DEAD_FLAG_SCHEMA_NAME, CollectionObjectConstants.DEAD_FLAG_FIELD_NAME);
					context.setProperty(PREVIOUS_DEAD_FLAG_PROPERTY_NAME, previousDeadFlag);
					
					List<String> previousTaxonNames = getTaxonNames(previousDoc);
					context.setProperty(PREVIOUS_TAXON_NAMES_PROPERTY_NAME, previousTaxonNames.toArray(new String[previousTaxonNames.size()]));
				}
				else {
					boolean deadFlagChanged = false;
					Set<String> deletedTaxonNames = null;
					Set<String> addedTaxonNames = null;
					
					if (event.getName().equals(DocumentEventTypes.DOCUMENT_UPDATED)) {						
						// As an optimization, check if the dead flag of the collectionobject has
						// changed, or if the taxonomic identification has changed. If so, we need to
						// update the access codes of referenced taxon records.

						String previousDeadFlag = (String) context.getProperty(PREVIOUS_DEAD_FLAG_PROPERTY_NAME);
						String currentDeadFlag = (String) doc.getProperty(CollectionObjectConstants.DEAD_FLAG_SCHEMA_NAME, CollectionObjectConstants.DEAD_FLAG_FIELD_NAME);

						if (previousDeadFlag == null) {
							previousDeadFlag = "";
						}
						
						if (currentDeadFlag == null) {
							currentDeadFlag = "";
						}
												
						if (previousDeadFlag.equals(currentDeadFlag)) {
							logger.debug("dead flag not changed: previousDeadFlag=" + previousDeadFlag + " currentDeadFlag=" + currentDeadFlag);
						}
						else {
							logger.debug("dead flag changed: previousDeadFlag=" + previousDeadFlag + " currentDeadFlag=" + currentDeadFlag);
							deadFlagChanged = true;
						}
						
						List<String> previousTaxonNames = Arrays.asList((String[]) context.getProperty(PREVIOUS_TAXON_NAMES_PROPERTY_NAME));
						List<String> currentTaxonNames = getTaxonNames(doc);
						
						deletedTaxonNames = findDeletedTaxonNames(previousTaxonNames, currentTaxonNames);
						logger.debug("found deleted taxon names: " + StringUtils.join(deletedTaxonNames, ", "));

						addedTaxonNames = findAddedTaxonNames(previousTaxonNames, currentTaxonNames);
						logger.debug("found added taxon names: " + StringUtils.join(addedTaxonNames, ", "));					
					}
					else if (event.getName().equals(DocumentEventTypes.DOCUMENT_CREATED)) {
						deadFlagChanged = true;
					}
					
					UpdateAccessCodeBatchJob updater = createUpdater();
					
					if (deadFlagChanged) {
						String collectionObjectCsid = doc.getName();
						
						try {
							// Pass false for the second parameter to updateReferencedAccessCodes, so that it doesn't
						 	// propagate changes up the taxon hierarchy. Propagation is taken care of by this
							// event handler: As taxon records are modified, this handler executes, and updates the
							// parent.
						
							InvocationResults results = updater.updateReferencedAccessCodes(collectionObjectCsid, false);
			
							logger.debug("updateReferencedAccessCodes complete: numAffected=" + results.getNumAffected() + " userNote=" + results.getUserNote());
						}
						catch (Exception e) {
							logger.error(e.getMessage(), e);
						}
					}
					else {
						// If the dead flag didn't change, we still need to recalculate the access codes of
						// any taxonomic idents that were added.
						
						if (addedTaxonNames != null) {
							for (String addedTaxonName : addedTaxonNames) {
								logger.debug("updating added taxon: " + addedTaxonName);

								try {									
									InvocationResults results = updater.updateAccessCode(addedTaxonName, false);
						
									logger.debug("updateAccessCode complete: numAffected=" + results.getNumAffected() + " userNote=" + results.getUserNote());
								}
								catch (Exception e) {
									logger.error(e.getMessage(), e);
								}						
							}							
						}
					}
					
					if (deletedTaxonNames != null) {
						// If any taxonomic idents were removed from the collectionobject, they need to have their
						// access codes recalculated.

						for (String deletedTaxonName : deletedTaxonNames) {
							logger.debug("updating deleted taxon: " + deletedTaxonName);

							try {									
								InvocationResults results = updater.updateAccessCode(deletedTaxonName, false);
					
								logger.debug("updateAccessCode complete: numAffected=" + results.getNumAffected() + " userNote=" + results.getUserNote());
							}
							catch (Exception e) {
								logger.error(e.getMessage(), e);
							}						
						}
					}
				}
			}
			else if (doc.getType().startsWith(TaxonConstants.NUXEO_DOCTYPE) &&
					!doc.isVersion() && 
					!doc.isProxy() && 
					!doc.getCurrentLifeCycleState().equals(WorkflowClient.WORKFLOWSTATE_DELETED)) {
				
				if (event.getName().equals(DocumentEventTypes.BEFORE_DOC_UPDATE)) {					
					// Stash the previous access code value, so it can be retrieved in the documentModified handler.

					DocumentModel previousDoc = (DocumentModel) context.getProperty(CoreEventConstants.PREVIOUS_DOCUMENT_MODEL);
					String previousAccessCode = (String) previousDoc.getProperty(TaxonConstants.ACCESS_CODE_SCHEMA_NAME, TaxonConstants.ACCESS_CODE_FIELD_NAME);

					context.setProperty(PREVIOUS_ACCESS_CODE_PROPERTY_NAME, previousAccessCode);
				}
				else {
					boolean updateRequired = false;

					if (event.getName().equals(DocumentEventTypes.DOCUMENT_UPDATED)) {				
						// As an optimization, check if the access code of the taxon has
						// changed. We only need to update the access code of the parent taxon
						// record if it has.

						String previousAccessCode = (String) context.getProperty(PREVIOUS_ACCESS_CODE_PROPERTY_NAME);
						String currentAccessCode = (String) doc.getProperty(TaxonConstants.ACCESS_CODE_SCHEMA_NAME, TaxonConstants.ACCESS_CODE_FIELD_NAME);

						if (previousAccessCode == null) {
							previousAccessCode = "";
						}
						
						if (currentAccessCode == null) {
							currentAccessCode = "";
						}
						
						if (previousAccessCode.equals(currentAccessCode)) {
							logger.debug("update not required: previousAccessCode=" + previousAccessCode + " currentAccessCode=" + currentAccessCode);
						}
						else {
							logger.debug("update required: previousAccessCode=" + previousAccessCode + " currentAccessCode=" + currentAccessCode);
							updateRequired = true;
						}
					}
					else if (event.getName().equals(DocumentEventTypes.DOCUMENT_CREATED)) {
						updateRequired = true;	
					}
				
					if (updateRequired) {
						String taxonCsid = doc.getName();
						
						try {
							// Pass false for the second parameter to updateReferencedAccessCodes, so that it doesn't
						 	// propagate changes up the taxon hierarchy. Propagation is taken care of by this
							// event handler: As taxon records are modified, this handler executes, and updates the
							// parent.
							
							InvocationResults results = createUpdater().updateParentAccessCode(taxonCsid, false);
			
							logger.debug("updateParentAccessCode complete: numAffected=" + results.getNumAffected() + " userNote=" + results.getUserNote());
						}
						catch (Exception e) {
							logger.error(e.getMessage(), e);
						}
					}
				}
			}
			else if (doc.getType().equals(RelationConstants.NUXEO_DOCTYPE) &&
					!doc.isVersion() && 
					!doc.isProxy()) {
				
				if (event.getName().equals(DocumentEventTypes.DOCUMENT_CREATED)) {
					String subjectDocType = (String) doc.getProperty(RelationConstants.SUBJECT_DOCTYPE_SCHEMA_NAME, RelationConstants.SUBJECT_DOCTYPE_FIELD_NAME);
					String objectDocType = (String) doc.getProperty(RelationConstants.OBJECT_DOCTYPE_SCHEMA_NAME, RelationConstants.OBJECT_DOCTYPE_FIELD_NAME);;
					String relationType = (String) doc.getProperty(RelationConstants.TYPE_SCHEMA_NAME, RelationConstants.TYPE_FIELD_NAME);
	
					logger.debug("subjectDocType=" + subjectDocType + " objectDocType=" + objectDocType + " relationType=" + relationType);
	
					if (subjectDocType.equals(TaxonConstants.NUXEO_DOCTYPE) && objectDocType.equals(TaxonConstants.NUXEO_DOCTYPE) && relationType.equals(RelationConstants.BROADER_TYPE)) {
						String parentTaxonCsid = (String) doc.getProperty(RelationConstants.OBJECT_CSID_SCHEMA_NAME, RelationConstants.OBJECT_CSID_FIELD_NAME);
						logger.debug("child added, updating parent taxon: parentTaxonCsid=" + parentTaxonCsid);
						
						try {							
							InvocationResults results = createUpdater().updateAccessCode(parentTaxonCsid, false);
							
							logger.debug("updateAccessCode complete: numAffected=" + results.getNumAffected() + " userNote=" + results.getUserNote());
						}
						catch (Exception e) {
							logger.error(e.getMessage(), e);
						}
					}
				}
				else if (event.getName().equals(DocumentEventTypes.ABOUT_TO_REMOVE)) {
					String subjectDocType = (String) doc.getProperty(RelationConstants.SUBJECT_DOCTYPE_SCHEMA_NAME, RelationConstants.SUBJECT_DOCTYPE_FIELD_NAME);
					String objectDocType = (String) doc.getProperty(RelationConstants.OBJECT_DOCTYPE_SCHEMA_NAME, RelationConstants.OBJECT_DOCTYPE_FIELD_NAME);;
					String relationType = (String) doc.getProperty(RelationConstants.TYPE_SCHEMA_NAME, RelationConstants.TYPE_FIELD_NAME);
	
					logger.debug("subjectDocType=" + subjectDocType + " objectDocType=" + objectDocType + " relationType=" + relationType);

					if (subjectDocType.equals(TaxonConstants.NUXEO_DOCTYPE) && objectDocType.equals(TaxonConstants.NUXEO_DOCTYPE) && relationType.equals(RelationConstants.BROADER_TYPE)) {
						String parentTaxonCsid = (String) doc.getProperty(RelationConstants.OBJECT_CSID_SCHEMA_NAME, RelationConstants.OBJECT_CSID_FIELD_NAME);
						
						// Stash the parent taxon csid, so it can be retrieved in the documentRemoved handler.
						logger.debug("about to delete taxon hierarchy relation: parentTaxonCsid=" + parentTaxonCsid);
						context.setProperty(DELETED_RELATION_PARENT_CSID_PROPERTY_NAME, parentTaxonCsid);
					}
				}
				else if (event.getName().equals(DocumentEventTypes.DOCUMENT_REMOVED)) {
					String parentTaxonCsid = (String) context.getProperty(DELETED_RELATION_PARENT_CSID_PROPERTY_NAME);
					
					if (StringUtils.isNotEmpty(parentTaxonCsid)) {
						logger.debug("child removed, updating parent taxon: parentTaxonCsid=" + parentTaxonCsid);

						try {							
							InvocationResults results = createUpdater().updateAccessCode(parentTaxonCsid, false);
							
							logger.debug("updateAccessCode complete: numAffected=" + results.getNumAffected() + " userNote=" + results.getUserNote());
						}
						catch (Exception e) {
							logger.error(e.getMessage(), e);
						}						
					}
				}
			}
		}
	}
	
	private List<String> getTaxonNames(DocumentModel doc) throws ClientException {
		List<Map<String, Object>> taxonomicIdentGroupList = (List<Map<String, Object>>) doc.getProperty(CollectionObjectConstants.TAXON_SCHEMA_NAME, TAXONOMIC_IDENT_GROUP_LIST_FIELD_NAME);
		List<String> taxonNames = new ArrayList<String>();

		for (Map<String, Object> taxonomicIdentGroup : taxonomicIdentGroupList) {
			String taxonName = (String) taxonomicIdentGroup.get(TAXON_FIELD_NAME);

			if (StringUtils.isNotEmpty(taxonName)) {
				taxonNames.add(taxonName);
			}
		}

		return taxonNames;
	}

	private Set<String> findDeletedTaxonNames(List<String> previousTaxonNames, List<String> currentTaxonNames) {
		Set<String> currentTaxonNameSet = new HashSet<String>(currentTaxonNames);
		Set<String> deletedTaxonNameSet = new HashSet<String>();
		
		for (String previousTaxonName : previousTaxonNames) {
			if (!currentTaxonNameSet.contains(previousTaxonName)) {
				deletedTaxonNameSet.add(previousTaxonName);
			}
		}
		
		return deletedTaxonNameSet;
	}
	
	private Set<String> findAddedTaxonNames(List<String> previousTaxonNames, List<String> currentTaxonNames) {
		Set<String> previousTaxonNameSet = new HashSet<String>(previousTaxonNames);
		Set<String> addedTaxonNameSet = new HashSet<String>();
		
		for (String currentTaxonName : currentTaxonNames) {
			if (!previousTaxonNameSet.contains(currentTaxonName)) {
				addedTaxonNameSet.add(currentTaxonName);
			}
		}
		
		return addedTaxonNameSet;
	}
	
	private UpdateAccessCodeBatchJob createUpdater() {
		ResourceMap resourceMap = ResteasyProviderFactory.getContextData(ResourceMap.class);

		UpdateAccessCodeBatchJob updater = new UpdateAccessCodeBatchJob();
		updater.setResourceMap(resourceMap);

		return updater;
	}
}