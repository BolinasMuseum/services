package org.collectionspace.services.common.imaging.nuxeo;

import java.io.File;
import java.io.Serializable;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.convert.api.ConversionException;
import org.nuxeo.ecm.core.convert.cache.SimpleCachableBlobHolder;
import org.nuxeo.ecm.core.convert.extension.Converter;
import org.nuxeo.ecm.core.convert.extension.ConverterDescriptor;
import org.nuxeo.ecm.core.storage.sql.coremodel.SQLBlob;
import org.nuxeo.ecm.platform.commandline.executor.api.CmdParameters;
import org.nuxeo.ecm.platform.commandline.executor.api.CommandAvailability;
import org.nuxeo.ecm.platform.commandline.executor.api.CommandLineExecutorService;
import org.nuxeo.ecm.platform.commandline.executor.api.ExecResult;
import org.nuxeo.ecm.platform.picture.core.im.IMImageUtils;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.services.streaming.FileSource;
import org.nuxeo.runtime.services.streaming.StreamSource;

public class ThumbnailConverter extends IMImageUtils implements Converter {
	private static final Log log = LogFactory.getLog(ThumbnailConverter.class);

	@Override
	public BlobHolder convert(BlobHolder blobHolder,
			Map<String, Serializable> parameters) throws ConversionException {
		try {
			// Make sure the toThumbnail command is available
			CommandLineExecutorService cles = Framework
					.getLocalService(CommandLineExecutorService.class);
			CommandAvailability commandAvailability = cles
					.getCommandAvailability("toThumbnail");
			if (!commandAvailability.isAvailable()) {
				return null;
			}
			// get the input and output of the command
			Blob blob = blobHolder.getBlob();
			File inputFile = null;
			if (blob instanceof FileBlob) {
				inputFile = ((FileBlob) blob).getFile();
			} else if (blob instanceof SQLBlob) {
				StreamSource source = ((SQLBlob) blob).getBinary()
						.getStreamSource();
				inputFile = ((FileSource) source).getFile();
			}
			if (inputFile == null) {
				return null;
			}
			CmdParameters params = new CmdParameters();
			File outputFile = File.createTempFile("nuxeoImageTarget", "."
					+ "png");
			params.addNamedParameter("inputFilePath", inputFile);
			params.addNamedParameter("outputFilePath", outputFile);

			ExecResult res = cles.execCommand("toThumbnail", params);
			if (!res.isSuccessful()) {
				return null;
			}
			Blob targetBlob = new FileBlob(outputFile);
			Framework.trackFile(outputFile, targetBlob);
			return new SimpleCachableBlobHolder(targetBlob);
		} catch (Exception e) {
			throw new ConversionException("Thumbnail conversion has failed", e);
		}
	}

	@Override
	public void init(ConverterDescriptor descriptor) {
	}
}
