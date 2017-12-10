package com.emc.mongoose.storage.driver.fs;

import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.io.task.data.DataIoTask;
import com.emc.mongoose.api.model.item.DataItem;
import com.emc.mongoose.ui.log.LogUtil;

import org.apache.logging.log4j.Level;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashSet;
import java.util.Set;

public interface FsConstants {

	FileSystem FS = FileSystems.getDefault();
	FileSystemProvider FS_PROVIDER = FS.provider();

	Set<OpenOption> CREATE_OPEN_OPT = new HashSet<OpenOption>() {
		{
			add(StandardOpenOption.CREATE);
			add(StandardOpenOption.TRUNCATE_EXISTING);
			add(StandardOpenOption.WRITE);
		}
	};
	Set<OpenOption> READ_OPEN_OPT = new HashSet<OpenOption>() {
		{
			add(StandardOpenOption.READ);
		}
	};
	Set<OpenOption> WRITE_OPEN_OPT = new HashSet<OpenOption>() {
		{
			add(StandardOpenOption.WRITE);
		}
	};

	static <I extends DataItem, O extends DataIoTask<I>> FileChannel openSrcFile(final O ioTask) {
		final String srcPath = ioTask.getSrcPath();
		if(srcPath == null || srcPath.isEmpty()) {
			return null;
		}
		final String fileItemName = ioTask.getItem().getName();
		final Path srcFilePath = srcPath.isEmpty() || fileItemName.startsWith(srcPath) ?
			FS.getPath(fileItemName) : FS.getPath(srcPath, fileItemName);
		try {
			return FS_PROVIDER.newFileChannel(srcFilePath, READ_OPEN_OPT);
		} catch(final IOException e) {
			LogUtil.exception(
				Level.WARN, e, "Failed to open the source channel for the path @ \"{}\"",
				srcFilePath
			);
			ioTask.setStatus(IoTask.Status.FAIL_IO);
			return null;
		}
	}

	static File createParentDir(final String parentPath) {
		try {
			final File parentDir = FS.getPath(parentPath).toFile();
			if(!parentDir.exists()) {
				parentDir.mkdirs();
			}
			return parentDir;
		} catch(final Exception e) {
			return null;
		}
	}
}
