package org.springframework.batch.item.file;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.util.ExecutionContextUserSupport;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * Wraps a {@link ResourceAwareItemWriterItemStream} and creates a new output
 * resource when the count of items written in current resource exceeds
 * {@link #setItemCountLimitPerResource(int)}. Suffix creation can be customized
 * with {@link #setResourceSuffixCreator(ResourceSuffixCreator)}.
 * 
 * Note that new resources are created only at chunk boundaries i.e. the number
 * of items written into one resource is between the limit set by
 * {@link #setItemCountLimitPerResource(int)} and (limit + chunk size).
 * 
 * @param <T> item type
 * 
 * @author Robert Kasanicky
 */
public class MultiResourceItemWriter<T> extends ExecutionContextUserSupport implements ItemWriter<T>, ItemStream {

	final static private String RESOURCE_INDEX_KEY = "resource.index";

	final static private String CURRENT_RESOURCE_ITEM_COUNT = "resource.item.count";

	private Resource resource;

	private ResourceAwareItemWriterItemStream<? super T> delegate;

	private int itemCountLimitPerResource = Integer.MAX_VALUE;

	private int currentResourceItemCount = 0;

	private int resourceIndex = 1;

	private ResourceSuffixCreator suffixCreator = new SimpleResourceSuffixCreator();

	public MultiResourceItemWriter() {
		setName(ClassUtils.getShortName(MultiResourceItemWriter.class));
	}

	public void write(List<? extends T> items) throws Exception {
		if (currentResourceItemCount >= itemCountLimitPerResource) {
			delegate.close(new ExecutionContext());
			resourceIndex++;
			currentResourceItemCount = 0;
			pointDelegateToNextResource();
			delegate.open(new ExecutionContext());
		}
		delegate.write(items);
		currentResourceItemCount += items.size();
	}

	/**
	 * Allows customization of the suffix of the created resources based on the
	 * index.
	 */
	public void setResourceSuffixCreator(ResourceSuffixCreator suffixCreator) {
		this.suffixCreator = suffixCreator;
	}

	/**
	 * After this limit is exceeded the next chunk will be written into newly
	 * created resource.
	 */
	public void setItemCountLimitPerResource(int itemCountLimitPerResource) {
		this.itemCountLimitPerResource = itemCountLimitPerResource;
	}

	/**
	 * Delegate used for actual writing of the output.
	 */
	public void setDelegate(ResourceAwareItemWriterItemStream<? super T> delegate) {
		this.delegate = delegate;
	}

	/**
	 * Prototype for output resources. Actual output files will be created in
	 * the same directory and use the same name as this prototype with appended
	 * suffix (according to {@link #setResourceSuffixCreator(ResourceSuffixCreator)}.
	 */
	public void setResource(Resource resource) {
		this.resource = resource;
	}

	public void close(ExecutionContext executionContext) throws ItemStreamException {
		resourceIndex = 1;
		currentResourceItemCount = 0;
		delegate.close(executionContext);
	}

	public void open(ExecutionContext executionContext) throws ItemStreamException {
		resourceIndex = Long.valueOf(executionContext.getLong(getKey(RESOURCE_INDEX_KEY), 1L)).intValue();
		currentResourceItemCount = Long.valueOf(executionContext.getLong(getKey(CURRENT_RESOURCE_ITEM_COUNT), 0L))
				.intValue();
		try {
			pointDelegateToNextResource();
		}
		catch (IOException e) {
			throw new ItemStreamException("Couldn't open resource", e);
		}
		delegate.open(executionContext);
	}

	public void update(ExecutionContext executionContext) throws ItemStreamException {
		delegate.update(executionContext);
		executionContext.put(getKey(CURRENT_RESOURCE_ITEM_COUNT), Long.valueOf(currentResourceItemCount));
		executionContext.put(getKey(RESOURCE_INDEX_KEY), Long.valueOf(resourceIndex));
	}

	/**
	 * Create next output resource and point the delegate to it.
	 */
	private void pointDelegateToNextResource() throws IOException {
		String path = resource.getFile().getAbsolutePath() + suffixCreator.getSuffix(resourceIndex);
		File file = new File(path);
		file.createNewFile();
		Assert.state(file.canWrite(), "Output resource " + path + " must be writable");
		delegate.setResource(new FileSystemResource(file));
	}
}
