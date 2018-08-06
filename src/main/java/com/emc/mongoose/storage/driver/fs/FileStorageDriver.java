package com.emc.mongoose.storage.driver.fs;

import com.emc.mongoose.api.common.exception.OmgShootMyFootException;
import com.emc.mongoose.api.model.data.DataCorruptionException;
import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.api.model.data.DataSizeException;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.io.task.data.DataIoTask;
import com.emc.mongoose.api.model.io.task.path.PathIoTask;
import com.emc.mongoose.api.model.item.DataItem;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.api.model.item.ItemFactory;
import com.emc.mongoose.api.model.storage.Credential;
import com.emc.mongoose.storage.driver.nio.base.NioStorageDriver;
import com.emc.mongoose.storage.driver.nio.base.NioStorageDriverBase;
import com.emc.mongoose.ui.config.load.LoadConfig;
import com.emc.mongoose.ui.config.storage.StorageConfig;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;
import com.github.akurilov.commons.collection.Range;
import org.apache.logging.log4j.Level;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.emc.mongoose.api.model.io.task.IoTask.Status;
import static com.emc.mongoose.storage.driver.fs.FsConstants.CREATE_OPEN_OPT;
import static com.emc.mongoose.storage.driver.fs.FsConstants.FS;
import static com.emc.mongoose.storage.driver.fs.FsConstants.FS_PROVIDER;
import static com.emc.mongoose.storage.driver.fs.FsConstants.WRITE_OPEN_OPT;

/**
 Created by kurila on 19.07.16.
 */
public final class FileStorageDriver<I extends Item, O extends IoTask<I>>
	extends NioStorageDriverBase<I, O>
	implements NioStorageDriver<I, O> {

	private final Map<DataIoTask, FileChannel> srcOpenFiles = new ConcurrentHashMap<>();
	private final Map<String, File> dstParentDirs = new ConcurrentHashMap<>();
	private final Map<DataIoTask, FileChannel> dstOpenFiles = new ConcurrentHashMap<>();

	public FileStorageDriver(
		final String jobName, final DataInput contentSrc, final LoadConfig loadConfig,
		final StorageConfig storageConfig, final boolean verifyFlag
	)
	throws OmgShootMyFootException {
		super(jobName, contentSrc, loadConfig, storageConfig, verifyFlag);
		requestAuthTokenFunc = null; // do not use
	}

	private <F extends DataItem, D extends DataIoTask<F>> FileChannel openDstFile(final D ioTask) {
		final String fileItemName = ioTask.getItem().getName();
		final IoType ioType = ioTask.getIoType();
		String dstPath = ioTask.getDstPath();
		final Path itemPath;
		if(dstPath != null && dstPath.startsWith("/")) {
			dstPath = dstPath.substring(1);
		}
		try {
			if(dstPath == null || dstPath.isEmpty() || fileItemName.startsWith(dstPath)) {
				itemPath = FS.getPath(fileItemName);
			} else {
				dstParentDirs.computeIfAbsent(dstPath, FsConstants::createParentDir);
				itemPath = FS.getPath(dstPath, fileItemName);
			}
			if(IoType.CREATE.equals(ioType)) {
				return FS_PROVIDER.newFileChannel(itemPath, CREATE_OPEN_OPT);
			} else {
				return FS_PROVIDER.newFileChannel(itemPath, WRITE_OPEN_OPT);
			}
		} catch(final AccessDeniedException e) {
			ioTask.setStatus(IoTask.Status.RESP_FAIL_AUTH);
			LogUtil.exception(Level.DEBUG, e, "Access denied to open the output channel for the path \"{}\"", dstPath);
		} catch(final NoSuchFileException e) {
			ioTask.setStatus(IoTask.Status.FAIL_IO);
			LogUtil.exception(Level.DEBUG, e, "Failed to open the output channel for the path \"{}\"", dstPath);
		} catch(final FileSystemException e) {
			final long freeSpace = (new File(e.getFile())).getFreeSpace();
			if(freeSpace > 0) {
				ioTask.setStatus(IoTask.Status.FAIL_IO);
				LogUtil.exception(Level.DEBUG, e, "Failed to open the output channel for the path \"{}\"", dstPath);
			} else {
				ioTask.setStatus(IoTask.Status.RESP_FAIL_SPACE);
				LogUtil.exception(Level.DEBUG, e, "No free space for the path \"{}\"", dstPath);
			}
		} catch(final IOException e) {
			ioTask.setStatus(IoTask.Status.FAIL_IO);
			LogUtil.exception(Level.DEBUG, e, "Failed to open the output channel for the path \"{}\"", dstPath);
		} catch(final Throwable cause) {
			ioTask.setStatus(IoTask.Status.FAIL_UNKNOWN);
			LogUtil.exception(Level.WARN, cause, "Failed to open the output channel for the path \"{}\"", dstPath);
		}
		return null;
	}

	@Override
	protected final String requestNewPath(final String path) {
		final File pathFile = FS.getPath((! path.startsWith("/")) ? path : path.substring(1)).toFile();
		if(! pathFile.exists()) {
			pathFile.mkdirs();
		}
		return path;
	}

	@Override
	protected final String requestNewAuthToken(final Credential credential) {
		throw new AssertionError("Should not be invoked");
	}

	@Override
	public List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	)
	throws IOException {
		return ListingHelper.list(itemFactory, path, prefix, idRadix, lastPrevItem, count);
	}

	@Override
	public final void adjustIoBuffers(final long avgTransferSize, final IoType ioType) {
	}

	@Override
	protected final void invokeNio(final O ioTask) {
		if(ioTask instanceof DataIoTask) {
			invokeFileNio((DataIoTask<? extends DataItem>) ioTask);
		} else if(ioTask instanceof PathIoTask) {
			throw new AssertionError("Not implemented");
		} else {
			throw new AssertionError("Not implemented");
		}
	}

	protected final <F extends DataItem, D extends DataIoTask<F>> void invokeFileNio(
		final D ioTask
	) {
		FileChannel srcChannel = null;
		FileChannel dstChannel = null;
		try {
			final IoType ioType = ioTask.getIoType();
			final F item = ioTask.getItem();
			switch(ioType) {
				case NOOP:
					finishIoTask((O) ioTask);
					break;
				case CREATE:
					dstChannel = dstOpenFiles.computeIfAbsent(ioTask, this::openDstFile);
					srcChannel = srcOpenFiles.computeIfAbsent(ioTask, FsConstants::openSrcFile);
					if(dstChannel == null) {
						break;
					}
					if(srcChannel == null) {
						if(ioTask.getStatus().equals(Status.FAIL_IO)) {
							break;
						} else {
							if(FileIoHelper.invokeCreate(item, ioTask, dstChannel)) {
								finishIoTask((O) ioTask);
							}
						}
					} else { // copy the data from the src channel to the dst channel
						if(FileIoHelper.invokeCopy(item, ioTask, srcChannel, dstChannel)) {
							finishIoTask((O) ioTask);
						}
					}
					break;
				case READ:
					srcChannel = srcOpenFiles.computeIfAbsent(ioTask, FsConstants::openSrcFile);
					if(srcChannel == null) {
						break;
					}
					final List<Range> fixedRangesToRead = ioTask.getFixedRanges();
					if(verifyFlag) {
						try {
							if(fixedRangesToRead == null || fixedRangesToRead.isEmpty()) {
								if(ioTask.hasMarkedRanges()) {
									if(FileIoHelper.invokeReadAndVerifyRandomRanges(item, ioTask, srcChannel,
										ioTask.getMarkedRangesMaskPair()
									)) {
										ioTask.setCountBytesDone(ioTask.getMarkedRangesSize());
										finishIoTask((O) ioTask);
									}
								} else {
									if(FileIoHelper.invokeReadAndVerify(item, ioTask, srcChannel)) {
										finishIoTask((O) ioTask);
									}
								}
							} else {
								if(FileIoHelper.invokeReadAndVerifyFixedRanges(
									item, ioTask, srcChannel, fixedRangesToRead)) {
									finishIoTask((O) ioTask);
								}
								;
							}
						} catch(final DataSizeException e) {
							ioTask.setStatus(Status.RESP_FAIL_CORRUPT);
							final long countBytesDone = ioTask.getCountBytesDone() + e.getOffset();
							ioTask.setCountBytesDone(countBytesDone);
							Loggers.MSG.debug("{}: content size mismatch, expected: {}, actual: {}", item.getName(),
								item.size(), countBytesDone
							);
						} catch(final DataCorruptionException e) {
							ioTask.setStatus(Status.RESP_FAIL_CORRUPT);
							final long countBytesDone = ioTask.getCountBytesDone() + e.getOffset();
							ioTask.setCountBytesDone(countBytesDone);
							Loggers.MSG.debug("{}: content mismatch @ offset {}, expected: {}, actual: {} ",
								item.getName(), countBytesDone, String.format("\"0x%X\"", (int) (e.expected & 0xFF)),
								String.format("\"0x%X\"", (int) (e.actual & 0xFF))
							);
						}
					} else {
						if(fixedRangesToRead == null || fixedRangesToRead.isEmpty()) {
							if(ioTask.hasMarkedRanges()) {
								if(FileIoHelper.invokeReadRandomRanges(
									item, ioTask, srcChannel, ioTask.getMarkedRangesMaskPair())) {
									finishIoTask((O) ioTask);
								}
							} else {
								if(FileIoHelper.invokeRead(item, ioTask, srcChannel)) {
									finishIoTask((O) ioTask);
								}
							}
						} else {
							if(FileIoHelper.invokeReadFixedRanges(item, ioTask, srcChannel, fixedRangesToRead)) {
								finishIoTask((O) ioTask);
							}
						}
					}
					break;
				case UPDATE:
					dstChannel = dstOpenFiles.computeIfAbsent(ioTask, this::openDstFile);
					if(dstChannel == null) {
						break;
					}
					final List<Range> fixedRangesToUpdate = ioTask.getFixedRanges();
					if(fixedRangesToUpdate == null || fixedRangesToUpdate.isEmpty()) {
						if(ioTask.hasMarkedRanges()) {
							if(FileIoHelper.invokeRandomRangesUpdate(item, ioTask, dstChannel)) {
								item.commitUpdatedRanges(ioTask.getMarkedRangesMaskPair());
								ioTask.setCountBytesDone(ioTask.getMarkedRangesSize());
								finishIoTask((O) ioTask);
							}
						} else {
							if(FileIoHelper.invokeOverwrite(item, ioTask, dstChannel)) {
								finishIoTask((O) ioTask);
							}
						}
					} else {
						if(FileIoHelper.invokeFixedRangesUpdate(item, ioTask, dstChannel, fixedRangesToUpdate)) {
							ioTask.setCountBytesDone(ioTask.getMarkedRangesSize());
							finishIoTask((O) ioTask);
						}
					}
					break;
				case DELETE:
					if(invokeDelete((O) ioTask)) {
						finishIoTask((O) ioTask);
					}
					break;
				default:
					ioTask.setStatus(Status.FAIL_UNKNOWN);
					Loggers.ERR.fatal("Unknown load type \"{}\"", ioType);
					break;
			}
		} catch(final FileNotFoundException e) {
			LogUtil.exception(Level.WARN, e, ioTask.toString());
			ioTask.setStatus(Status.RESP_FAIL_NOT_FOUND);
		} catch(final AccessDeniedException e) {
			LogUtil.exception(Level.WARN, e, ioTask.toString());
			ioTask.setStatus(Status.RESP_FAIL_AUTH);
		} catch(final ClosedChannelException e) {
			ioTask.setStatus(Status.INTERRUPTED);
		} catch(final IOException e) {
			LogUtil.exception(Level.WARN, e, ioTask.toString());
			ioTask.setStatus(Status.FAIL_IO);
		} catch(final NullPointerException e) {
			if(! isClosed()) { // shared content source may be already closed from the load generator
				e.printStackTrace(System.out);
				ioTask.setStatus(Status.FAIL_UNKNOWN);
			} else {
				Loggers.ERR.debug("I/O task caused NPE while being interrupted: {}", ioTask);
			}
		} catch(final Throwable e) {
			// should be Throwable here in order to make the closing block further always reachable
			// the same effect may be reached using "finally" block after this "catch"
			e.printStackTrace(System.err);
			ioTask.setStatus(Status.FAIL_UNKNOWN);
		}
		if(! Status.ACTIVE.equals(ioTask.getStatus())) {
			if(srcChannel != null) {
				srcOpenFiles.remove(ioTask);
				if(srcChannel.isOpen()) {
					try {
						srcChannel.close();
					} catch(final IOException e) {
						Loggers.ERR.warn("Failed to close the source I/O channel");
					}
				}
			}
			if(dstChannel != null) {
				dstOpenFiles.remove(ioTask);
				if(dstChannel.isOpen()) {
					try {
						dstChannel.close();
					} catch(final IOException e) {
						Loggers.ERR.warn("Failed to close the destination I/O channel");
					}
				}
			}
		}
	}

	private boolean invokeDelete(final O ioTask)
	throws IOException {
		final String dstPath = ioTask.getDstPath();
		final I fileItem = ioTask.getItem();
		FS_PROVIDER.delete(dstPath == null ? Paths.get(fileItem.getName()) : Paths.get(dstPath, fileItem.getName()));
		return true;
	}

	@Override
	protected final void doClose()
	throws IOException {
		super.doClose();
		for(final FileChannel srcChannel : srcOpenFiles.values()) {
			if(srcChannel.isOpen()) {
				srcChannel.close();
			}
		}
		srcOpenFiles.clear();
		for(final FileChannel dstChannel : dstOpenFiles.values()) {
			if(dstChannel.isOpen()) {
				dstChannel.close();
			}
		}
		dstOpenFiles.clear();
	}

	@Override
	public final String toString() {
		return String.format(super.toString(), "fs");
	}
}
