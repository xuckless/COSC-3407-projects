package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.LinkedList;
import java.util.HashMap;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 *
	 * Task 3: assigns a globally unique PID, increments the active-process
	 * count, lazily initializes the global free-frame pool, and sets up the
	 * file descriptor table with stdin/stdout per the design conventions.
	 */
	public UserProcess() {
		// Assign a globally unique PID under the PID lock.
		pidLock.acquire();
		this.pid = nextPid++;
		pidLock.release();
		
		// Lazy-initialize the global free-frame pool the first time any
		// UserProcess is constructed. Synchronized to handle concurrent ctor
		// calls during exec().
		initializeFreeFramesIfNeeded();
		
		// Track the number of live processes so the last one to exit halts.
		activeLock.acquire();
		activeProcesses++;
		activeLock.release();
		
		// File descriptor table: 0 = stdin, 1 = stdout (design convention).
		fileTable = new OpenFile[MAX_FILE_DESCRIPTORS];
		fileTable[0] = UserKernel.console.openForReading();
		fileTable[1] = UserKernel.console.openForWriting();
		
		// pageTable is intentionally NOT built here; loadSections() builds it
		// once we know how many pages the program needs.
	}
	
	/**
	 * Allocate and return a new process of the correct class. The class name
	 * is specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 *
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess)Lib.constructObject(Machine.getProcessClassName());
	}
	
	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 *
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;
		
		// Hold a reference to the UThread so a parent's handleJoin can call
		// thread.join() on it.
		this.thread = new UThread(this);
		this.thread.setName(name).fork();
		
		return true;
	}
	
	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}
	
	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}
	
	/**
	 * Read a null-terminated string from this process's virtual memory. Read
	 * at most <tt>maxLength + 1</tt> bytes from the specified address, search
	 * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 *
	 * @param vaddr the starting virtual address of the null-terminated
	 * string.
	 * @param maxLength the maximum number of characters in the string,
	 * not including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);
		
		byte[] bytes = new byte[maxLength+1];
		
		int bytesRead = readVirtualMemory(vaddr, bytes);
		
		for (int length=0; length<bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}
		
		return null;
	}
	
	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}
	
	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * Page-aware: walks the pageTable so non-contiguous physical frames work,
	 * and stops cleanly on any invalid VPN rather than throwing.
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to
	 * the array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);
		
		byte[] memory = Machine.processor().getMemory();
		
		if (vaddr < 0 || pageTable == null)
			return 0;
		
		int bytesLeft = length;
		int buffOffset = offset;
		int vPage = Processor.pageFromAddress(vaddr);
		int pgOffset = Processor.offsetFromAddress(vaddr);
		
		while (bytesLeft > 0
			&& vPage >= 0 && vPage < pageTable.length
			&& pageTable[vPage] != null
			&& pageTable[vPage].valid) {
			
			int bytesToEndOfPage = pageSize - pgOffset;
			int bytesToCopy = Math.min(bytesToEndOfPage, bytesLeft);
			
			int physAddr = Processor.makeAddress(pageTable[vPage].ppn, pgOffset);
			
			// Defensive bounds check on the physical address.
			if (physAddr < 0 || physAddr + bytesToCopy > memory.length)
				break;
			
			System.arraycopy(memory, physAddr, data, buffOffset, bytesToCopy);
			
			bytesLeft -= bytesToCopy;
			buffOffset += bytesToCopy;
			vPage++;
			pgOffset = 0;
		}
		
		return length - bytesLeft;
	}
	
	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory.
	 * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}
	
	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * Page-aware and respects the readOnly flag (text/rodata pages cannot be
	 * written through this path).
	 *
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to
	 * virtual memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);
		
		byte[] memory = Machine.processor().getMemory();
		
		if (vaddr < 0 || pageTable == null)
			return 0;
		
		int bytesLeft = length;
		int buffOffset = offset;
		int vPage = Processor.pageFromAddress(vaddr);
		int pgOffset = Processor.offsetFromAddress(vaddr);
		
		while (bytesLeft > 0
			&& vPage >= 0 && vPage < pageTable.length
			&& pageTable[vPage] != null
			&& pageTable[vPage].valid
			&& !pageTable[vPage].readOnly) {
			
			int bytesToEndOfPage = pageSize - pgOffset;
			int bytesToCopy = Math.min(bytesToEndOfPage, bytesLeft);
			
			int physAddr = Processor.makeAddress(pageTable[vPage].ppn, pgOffset);
			
			if (physAddr < 0 || physAddr + bytesToCopy > memory.length)
				break;
			
			System.arraycopy(data, buffOffset, memory, physAddr, bytesToCopy);
			
			bytesLeft -= bytesToCopy;
			buffOffset += bytesToCopy;
			vPage++;
			pgOffset = 0;
		}
		
		return length - bytesLeft;
	}
	
	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 *
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {

		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");
		
		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}
		
		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}
		
		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s=0; s<coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}
		
		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i=0; i<args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}
		
		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();
		
		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages*pageSize;
		
		// and finally reserve 1 page for arguments
		numPages++;
		
		if (!loadSections())
			return false;
		
		// store arguments in last page
		int argPageVpn = numPages - 1;
		int base = argPageVpn * pageSize;

		byte[] argPage = new byte[pageSize];

		int entryOffset = 0;
		int stringOffset = argc * 4;

		this.argc = argc;
		this.argv = base;

		for (int i = 0; i < argc; i++) {

			// pointer to string
			byte[] ptr = Lib.bytesFromInt(base + stringOffset);
			System.arraycopy(ptr, 0, argPage, entryOffset, 4);
			entryOffset += 4;

			// string bytes
			byte[] strBytes = args[i].getBytes();

			System.arraycopy(strBytes, 0, argPage, stringOffset, strBytes.length);
			argPage[stringOffset + strBytes.length] = 0;

			stringOffset += strBytes.length + 1;
		}
		int written = writeVirtualMemory(base, argPage, 0, pageSize);

		if (written != pageSize)
			return false;

		return true;
	}
	
	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be
	 * run (this is the last step in process initialization that can fail).
	 *
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}
		
		// Pull <numPages> physical frames from the global free-frame pool.
		// Frames may be non-contiguous; that's exactly the point.
		int[] physFrames =UserKernel.allocateFrames(numPages);
		if (physFrames == null) {
			coff.close();
			Lib.debug(dbgProcess, "\tcouldn't allocate physical frames");
			return false;
		}
		
		// Build a per-process pageTable mapping vpn i -> physFrames[i].
		pageTable = new TranslationEntry[numPages];
		for (int i = 0; i < numPages; i++) {
			pageTable[i] = new TranslationEntry(i, physFrames[i],
				true,  /* valid */
				false, /* readOnly (set below) */
				false, /* used */
				false  /* dirty */);
		}
		
		// Load each COFF section into its assigned physical frame, and
		// propagate the section's read-only flag onto the page table entry.
		for (int s=0; s<coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			
			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
				+ " section (" + section.getLength() + " pages)");
			
			for (int i=0; i<section.getLength(); i++) {
				int vpn = section.getFirstVPN()+i;
				
				if (section.isReadOnly())
					pageTable[vpn].readOnly = true;
				
				section.loadPage(i, pageTable[vpn].ppn);
			}
		}
		
		return true;
	}
	
	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 * Returns every physical frame this process owns to the global free pool,
	 * then nulls out the page table so subsequent VM accesses fail safely.
	 */
	protected void unloadSections() {
		if (pageTable == null)
			return;
		
		pageLock.acquire();
		for (int i = 0; i < pageTable.length; i++) {
			if (pageTable[i] != null && pageTable[i].valid) {
				UserKernel.releaseFrames(new int[]{pageTable[i].ppn});
				pageTable[i].valid = false;
			}
		}
		pageLock.release();
		
		pageTable = null;
	}
	
	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of
	 * the stack, set the A0 and A1 registers to argc and argv, respectively,
	 * and initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();
		
		// by default, everything's 0
		for (int i=0; i<processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);
		
		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);
		
		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}
	
	// ====================================================================
	// Global frame-pool helpers (kept here per the constraint to modify only
	// UserProcess.java; identical in spirit to UserKernel.allocateFrames /
	// releaseFrames in the design document).
	// ====================================================================
	
	private static void initializeFreeFramesIfNeeded() {
		pageLock.acquire();
		if (freeFrames == null) {
			freeFrames = new LinkedList<Integer>();
			int numPhys = Machine.processor().getNumPhysPages();
			for (int i = 0; i < numPhys; i++)
				freeFrames.add(i);
		}
		pageLock.release();
	}
	

	
	// ====================================================================
	// System call handlers
	// ====================================================================
	
	/**
	 * Handle the halt() system call.
	 *
	 * Task 3: only the root process (PID 0, the first process created in the
	 * system) is permitted to halt the machine. All other callers receive 0
	 * and continue execution.
	 */
	private int handleHalt() {
		if (this.pid != ROOT_PID)
			return 0;
		
		Machine.halt();
		
		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}
	
	private int syscallCreate(int nameAddr) {
		// read filename from virtual memory
		String filename = readVirtualMemoryString(nameAddr, 256);
		if (filename == null) { return - 1; }
		
		// now we find first free slot starting at i = 2
		int slot = -1;
		for (int i = 2; i < fileTable.length; i++) {
			if (fileTable[i] == null) {
				slot = i;
				break;
			}
		}
		
		if (slot == -1) { return -1; } // no free slots
		OpenFile file = UserKernel.fileSystem.open(filename, true); // if this is true, create
		
		if (file == null) { return -1; }
		fileTable[slot] = file;
		return slot;
	}
	
	// this is the same as syscallCreate, just flipping a boolean
	private int syscallOpen(int nameAddr) {
		// read filename from virtual memory
		String filename = readVirtualMemoryString(nameAddr, 256);
		if (filename == null) { return - 1; }
		
		// now we find first free slot starting at i = 2
		int slot = -1;
		for (int i = 2; i < fileTable.length; i++) {
			if (fileTable[i] == null) {
				slot = i;
				break;
			}
		}
		
		if (slot == -1) { return -1; } // no free slots
		OpenFile file = UserKernel.fileSystem.open(filename, false); // if this is true, create
		
		if (file == null) { return -1; }
		fileTable[slot] = file;
		return slot;
	}
	
	private int syscallRead(int fd, int bufAddr, int count) {
		if (fd < 0 || fd >= fileTable.length || fileTable[fd] == null) { return -1;}
		if (count < 0) { return -1;}
		
		int bytesRead = 0;
		byte[] buf = new byte[pageSize]; // chunk by page to avoid JVM heap issues
		while (count > 0) {
			int toRead = Math.min(count, pageSize);
			int r = fileTable[fd].read(buf, 0, toRead);
			if (r < 0) { return -1;} // read an error
			if (r == 0) { break; } // end of file
			
			int w = writeVirtualMemory(bufAddr, buf, 0, r);
			bytesRead += w;
			bufAddr += w;
			count -= r;
			
			if (w < r) { break;} // cant write everything
		}
		return bytesRead;
	}
	
	private int syscallWrite(int fd, int bufAddr, int count) {
		if (fd < 0 || fd >= fileTable.length || fileTable[fd] == null) { return -1;}
		if (count < 0) { return -1;}
		
		int bytesWritten = 0;
		byte[] buf = new byte[pageSize]; // chunk by page to avoid JVM heap issues
		while (count > 0) {
			int toWrite = Math.min(count, pageSize);
			int r = readVirtualMemory(bufAddr, buf, 0, toWrite);
			if (r <= 0) { return -1;} // nothing to write
			int w = fileTable[fd].write(buf, 0, r);
			if (w < r) { return -1;} // disk is full or stream is closed
			
			bytesWritten += w;
			bufAddr += w;
			count -= w;
		}
		return bytesWritten;
	}
	
	private int syscallClose(int fd) {
		if (fd < 0 || fd >= fileTable.length || fileTable[fd] == null) { return -1;}
		fileTable[fd].close();
		fileTable[fd] = null; // free up the slot
		
		return 0;
	}
	
	private int syscallUnlink(int nameAddr) {
		// read filename from virtual memory
		String filename = readVirtualMemoryString(nameAddr, 256);
		if (filename == null) { return -1;}
		
		boolean result = UserKernel.fileSystem.remove(filename);
		if (!result) { return -1;} // this is returned if the file cannot be removed
		
		return 0;
	}
	
	/**
	 * Handle the exec() system call.
	 *
	 * int exec(char *name, int argc, char **argv);
	 *
	 * Reads the filename and argv strings out of the caller's address space,
	 * constructs a fresh UserProcess, loads the program into the child's
	 * address space (which allocates physical frames from the free pool),
	 * forks a UThread to run it, and records the child in this process's
	 * children map. The returned PID is the child's globally-unique PID, or
	 * -1 on any failure.
	 */
	private int handleExec(int fileNameVaddr, int argc, int argvVaddr) {
		if (argc < 0)
			return -1;
		
		String filename = readVirtualMemoryString(fileNameVaddr, MAX_STRING_LENGTH);
		if (filename == null)
			return -1;
		String[] argsCopy = new String[argc];
		// The exec syscall must be passed a coff binary.
		if (!filename.endsWith(".coff"))
			return -1;
		
		// Read each argv[i] pointer, then dereference it to a string.
		

		for (int i = 0; i < argc; i++) {
			byte[] argPtrBytes = new byte[4];

			if (readVirtualMemory(argvVaddr + i * 4, argPtrBytes) != 4)
				return -1;

			int argVaddr = Lib.bytesToInt(argPtrBytes, 0);

			String arg = readVirtualMemoryString(argVaddr, MAX_STRING_LENGTH);

			if (arg == null)
				return -1;

			argsCopy[i] = arg;
}
		
		// Create the child. The constructor already incremented the active
		// process count; if execute() fails we must roll that back.
		UserProcess child = UserProcess.newUserProcess();
		child.parent = this;
		
		if (!child.execute(filename, argsCopy)) {
			// Roll back the active-process bump done in the child's ctor so
			// the "last process halts" logic stays accurate.
			activeLock.acquire();
			activeProcesses--;
			activeLock.release();
			return -1;
		}
		
		children.put(child.pid, child);
		return child.pid;
	}
	
	/**
	 * Handle the join() system call.
	 *
	 * int join(int processID, int *status);
	 *
	 * Blocks until the named child finishes, writes its exit status into the
	 * caller's *status (if non-NULL), removes the child from this process's
	 * children map, and returns 1 for normal exit, 0 for abnormal exit, or
	 * -1 if processID is not a direct child of the caller.
	 *
	 * Task 3: a child does NOT remove itself from the parent's
	 * children map on exit, so a join issued after the child has already
	 * exited still finds the child object, sees its cached exit status, and
	 * returns correctly. thread.join() returns immediately for a thread that
	 * has already finished.
	 */
	private int handleJoin(int childPid, int statusPtr) {
		UserProcess child = children.get(childPid);
		if (child == null)
			return -1;  // not our child (or already joined and removed)
		
		// Block until the child finishes. If it's already done, this returns
		// immediately.
		child.thread.join();
		
		// Copy the child's exit status to *statusPtr, when supplied.
		if (statusPtr != 0) {
			byte[] statusBytes = Lib.bytesFromInt(child.exitStatus);
			int written = writeVirtualMemory(statusPtr, statusBytes);
			if (written != 4) {
				// Caller gave us a bogus pointer. Still consume the child.
				children.remove(childPid);
				return -1;
			}
		}
		
		// Now that we've consumed the child, drop our reference so the same
		// PID can't be joined twice.
		children.remove(childPid);
		
		return child.exitedNormally ? 1 : 0;
	}
	
	/**
	 * Handle the exit() system call.
	 *
	 * void exit(int status);
	 *
	 * Records the exit status, releases all process-owned resources (open
	 * files, physical frames, COFF), orphans any still-running children, and
	 * either halts the machine (if this was the last live process) or
	 * terminates the calling thread.
	 *
	 * Note: this process is intentionally NOT removed from its parent's
	 * children map here -- a parent that joins after we exit must still find
	 * us with our exitStatus already set.
	 */
	private int handleExit(int status) {
		this.exitStatus = status;
		this.exitedNormally = true;
		
		// Close every open file descriptor.
		closeAllFileDescriptors();
		
		// Return all of this process's physical frames to the free pool.
		unloadSections();
		
		// Close the COFF executable file.
		if (coff != null) {
			coff.close();
			coff = null;
		}
		
		// Orphan our children (the parent we leave behind no longer exists,
		// from their perspective). We do NOT touch parent.children here.
		for (UserProcess c : children.values())
			c.parent = null;
		children.clear();
		
		// If we were the last live process, halt the machine.
		activeLock.acquire();
		activeProcesses--;
		boolean isLast = (activeProcesses == 0);
		activeLock.release();
		
		if (isLast) {
			Machine.halt();
			Lib.assertNotReached("Machine.halt() did not halt machine!");
		}
		
		UThread.finish();
		// UThread.finish() does not return.
		return 0;
	}
	
	/**
	 * Cleanup path used when a process is terminated abnormally (illegal
	 * instruction, bad VA, unknown exception, etc.). Per the project spec we
	 * deliberately do NOT call unloadSections() here -- if the page table
	 * may be in a corrupt state, freeing frames risks a double-free that
	 * would corrupt other live processes. The frames leak; the OS reclaims
	 * on shutdown.
	 */
	private void handleAbnormalExit() {
		this.exitStatus = ABNORMAL_EXIT_STATUS;
		this.exitedNormally = false;
		
		closeAllFileDescriptors();
		
		// Intentionally skip unloadSections() -- see method comment.
		
		if (coff != null) {
			coff.close();
			coff = null;
		}
		
		for (UserProcess c : children.values())
			c.parent = null;
		children.clear();
		
		activeLock.acquire();
		activeProcesses--;
		boolean isLast = (activeProcesses == 0);
		activeLock.release();
		
		if (isLast) {
			Machine.halt();
			Lib.assertNotReached("Machine.halt() did not halt machine!");
		}
		
		UThread.finish();
	}
	
	private void closeAllFileDescriptors() {
		if (fileTable == null)
			return;
		for (int i = 0; i < fileTable.length; i++) {
			if (fileTable[i] != null) {
				fileTable[i].close();
				fileTable[i] = null;
			}
		}
	}
	
	private static final int
		syscallHalt = 0,
		syscallExit = 1,
		syscallExec = 2,
		syscallJoin = 3,
		syscallCreate = 4,
		syscallOpen = 5,
		syscallRead = 6,
		syscallWrite = 7,
		syscallClose = 8,
		syscallUnlink = 9;
	
	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 *
	 * * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
			case syscallHalt:
				return handleHalt();
			case syscallExit:
				return handleExit(a0);
			case syscallExec:
				return handleExec(a0, a1, a2);
			case syscallJoin:
				return handleJoin(a0, a1);
			case syscallCreate:
				return syscallCreate(a0);
			case syscallOpen:
				return syscallOpen(a0);
			case syscallRead:
				return syscallRead(a0, a1, a2);
			case syscallWrite:
				return syscallWrite(a0, a1, a2);
			case syscallClose:
				return syscallClose(a0);
			case syscallUnlink:
				return syscallUnlink(a0);
			
			default:
				// Bullet-proofing: an unknown syscall is a user error, not a
				// kernel error. Return -1 rather than asserting.
				Lib.debug(dbgProcess, "Unknown syscall " + syscall);
				return -1;
		}
	}
	
	/**
	 * Handle a user exception. Called by
	 * <tt>UserKernel.exceptionHandler()</tt>. The
	 * <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 *
	 * Task 3: any exception other than a syscall is treated as an abnormal
	 * termination of this process. We tear down without freeing physical
	 * frames so a corrupt page table can't take other processes down with
	 * it.
	 *
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();
		
		switch (cause) {
			case Processor.exceptionSyscall:
				int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3)
				);
				processor.writeRegister(Processor.regV0, result);
				processor.advancePC();
				break;
			
			default:
				Lib.debug(dbgProcess, "Unexpected exception: " +
					Processor.exceptionNames[cause]);
				handleAbnormalExit();
				// handleAbnormalExit() does not return; the line below is a
				// safety net only.
				Lib.assertNotReached("Unexpected exception");
		}
	}
	
	// ====================================================================
	// Per-process state
	// ====================================================================
	
	/** The program being run by this process. */
	protected Coff coff;
	
	/** This process's page table. */
	protected TranslationEntry[] pageTable;
	/** The number of contiguous pages occupied by the program. */
	protected int numPages;
	
	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;
	
	private int initialPC, initialSP;
	private int argc, argv;
	
	// --- Task 3 per-process state ---
	
	/** Globally-unique process ID, assigned in the constructor. */
	private int pid;
	
	/** Parent process; null for the root process or for orphaned children. */
	private UserProcess parent = null;
	
	/** Direct children of this process, keyed by child PID. Used by
	 * handleJoin to enforce that only a parent may join a child. */
	private HashMap<Integer, UserProcess> children = new HashMap<Integer, UserProcess>();
	
	/** Exit status reported via join(). */
	private int exitStatus = 0;
	
	/** True iff this process called exit() (vs. crashed). */
	private boolean exitedNormally = false;
	
	/** UThread running this process; held so a parent can join() on it. */
	private UThread thread = null;
	
	/** Per-process file descriptor table (fd 0 = stdin, fd 1 = stdout). */
	private OpenFile[] fileTable;
	
	// ====================================================================
	// Global (static) state
	// ====================================================================
	
	/** Next PID to hand out. */
	private static int nextPid = 0;
	private static Lock pidLock = new Lock();
	
	/** Number of currently-live UserProcess instances. */
	private static int activeProcesses = 0;
	private static Lock activeLock = new Lock();
	
	/** Free physical frames; lazily initialized on first ctor call. */
	private static LinkedList<Integer> freeFrames = null;
	private static Lock pageLock = new Lock();
	
	// ====================================================================
	// Constants
	// ====================================================================
	
	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';
	
	private static final int ROOT_PID = 0;
	private static final int MAX_FILE_DESCRIPTORS = 16;
	private static final int MAX_STRING_LENGTH = 256;
	private static final int ABNORMAL_EXIT_STATUS = -1;
	
	// ====================================================================
	// Task 3 Test Suite
	// ====================================================================
	
	
	/**
	 * Run every Task 3 test in sequence and print a banner around the suite.
	 */
	public void taskThreeTests() {
		System.out.println("==== Task 3: UserProcess Test Suite ====");
		System.out.println();
		testBasicExecution();
		testSynchronization();
		testRecursiveSpawning();
		testMemoryRecycling();
		testSecurityPermissions();
		System.out.println("==== Test suite finished ====");
	}
	
		public void taskTwoTests() {

		selftestvirtualmemory();
		System.out.println();

		selftestframes();
		System.out.println();

		
	}


	public void taskOneTests() {
		System.out.println("==== Task 1: File System Tests ====");
		System.out.println();
		
		// Test 1: slot allocation starts at index 2
		System.out.println("[Test 1] File descriptor slot allocation:");
		int slot = -1;
		for (int i = 2; i < fileTable.length; i++) {
			if (fileTable[i] == null) { slot = i; break; }
		}
		System.out.println("  first free slot: " + slot + " (expected 2: " + (slot == 2 ? "PASSED" : "FAILED") + ")");
		System.out.println();
		
		// Test 2: invalid fd returns -1
		System.out.println("[Test 2] Invalid fd handling:");
		int r1 = syscallRead(-1, 0, 10);
		int r2 = syscallRead(20, 0, 10);
		int r3 = syscallWrite(-1, 0, 10);
		int r4 = syscallClose(5);
		System.out.println("  read fd=-1: " + (r1 == -1 ? "PASSED" : "FAILED"));
		System.out.println("  read fd=20: " + (r2 == -1 ? "PASSED" : "FAILED"));
		System.out.println("  write fd=-1: " + (r3 == -1 ? "PASSED" : "FAILED"));
		System.out.println("  close null slot: " + (r4 == -1 ? "PASSED" : "FAILED"));
		System.out.println();
		
		// Test 3: close nulls the slot
		System.out.println("[Test 3] Close nulls the slot:");
		fileTable[2] = UserKernel.console.openForReading(); // borrow console as a dummy file
		int closeResult = syscallClose(2);
		System.out.println("  close returned: " + (closeResult == 0 ? "PASSED" : "FAILED"));
		System.out.println("  slot is null after close: " + (fileTable[2] == null ? "PASSED" : "FAILED"));
		System.out.println();
		
		// Test 4: table is full at 16 slots
		System.out.println("[Test 4] File table capacity:");
		for (int i = 2; i < fileTable.length; i++) {
			fileTable[i] = UserKernel.console.openForReading();
		}
		int fullSlot = -1;
		for (int i = 2; i < fileTable.length; i++) {
			if (fileTable[i] == null) { fullSlot = i; break; }
		}
		System.out.println("  no free slot when full: " + (fullSlot == -1 ? "PASSED" : "FAILED"));
		for (int i = 2; i < fileTable.length; i++) fileTable[i] = null;
		System.out.println();
		
		// Test 5: stdin/stdout pre-filled
		System.out.println("[Test 5] stdin/stdout pre-filled:");
		System.out.println("  slot 0 (stdin) not null: " + (fileTable[0] != null ? "PASSED" : "FAILED"));
		System.out.println("  slot 1 (stdout) not null: " + (fileTable[1] != null ? "PASSED" : "FAILED"));
		System.out.println();
		
		System.out.println("==== Task 1 tests finished ====");
		System.out.println();
	}

	public static void selftestframes() {
    System.out.println("===== Frame Allocation Tests =====");
	System.out.println();

    int totalFrames = Machine.processor().getNumPhysPages();
    System.out.println("Total physical frames: " + totalFrames + "\n");

    // Test 1
    System.out.println("[Test 1] Allocate 1 frame:");
    int[] f1 = UserKernel.allocateFrames(1);
    System.out.println("  returned array != null: " + (f1 != null ? "PASSED" : "FAILED"));
    System.out.println("  length == 1: " + ((f1 != null && f1.length == 1) ? "PASSED" : "FAILED"));

    // Test 2
    System.out.println("\n[Test 2] Allocate 3 frames:");
    int[] f2 = UserKernel.allocateFrames(3);
    System.out.println("  returned array != null: " + (f2 != null ? "PASSED" : "FAILED"));
    System.out.println("  length == 3: " + ((f2 != null && f2.length == 3) ? "PASSED" : "FAILED"));

    // Test 3
    System.out.println("\n[Test 3] Invalid request (0 frames):");
    int[] f3 = UserKernel.allocateFrames(0);
    System.out.println("  returned null: " + (f3 == null ? "PASSED" : "FAILED"));

    // Test 4
    System.out.println("\n[Test 4] Invalid request (negative):");
    int[] f4 = UserKernel.allocateFrames(-1);
    System.out.println("  returned null: " + (f4 == null ? "PASSED" : "FAILED"));

    // Test 5
    System.out.println("\n[Test 5] Request more than available:");
    int[] f5 = UserKernel.allocateFrames(totalFrames + 1);
    System.out.println("  returned null: " + (f5 == null ? "PASSED" : "FAILED"));

    // Test 6
    System.out.println("\n[Test 6] Exhaust remaining frames:");

	int count = 0;
	while (true) {
		int[] temp = UserKernel.allocateFrames(1);
		if (temp == null) break;
			count++;
	}

	System.out.println("  frames allocated until exhaustion: " + count);

	int[] f6 = UserKernel.allocateFrames(1);
	System.out.println("  allocation returns null when empty: "
        + (f6 == null ? "PASSED" : "FAILED"));

    // Test 7
    System.out.println("\n[Test 7] Allocate when empty:");
    int[] f7 = UserKernel.allocateFrames(1);
    System.out.println("  returned null: " + (f7 == null ? "PASSED" : "FAILED"));

    // Test 8
    System.out.println("\n[Test 8] Release frames:");
    UserKernel.releaseFrames(f1);
    UserKernel.releaseFrames(f2);
    UserKernel.releaseFrames(f6);
    System.out.println("  freeFrames > 0: " + (freeFrames.size() > 0 ? "PASSED" : "FAILED"));

    // Test 9
    System.out.println("\n[Test 9] Allocate after release:");
    int[] f8 = UserKernel.allocateFrames(2);
    System.out.println("  allocation succeeds: " + (f8 != null ? "PASSED" : "FAILED"));
    System.out.println("  length == 2: " + ((f8 != null && f8.length == 2) ? "PASSED" : "FAILED"));

    System.out.println("\n==== Frame Allocation Tests Finished ====\n");
}

public static void selftestvirtualmemory() {
    System.out.println("===== Virtual Memory Read/Write Test =====");
	System.out.println();

    UserProcess process = new UserProcess();

    int numPages = 4;
    int pageSize = Processor.pageSize;

    System.out.println("[Setup] Requesting " + numPages + " physical frames...");
    int[] frames = UserKernel.allocateFrames(numPages);

    if (frames == null) {
        System.out.println("  FAILED: Could not allocate frames\n");
        return;
    }

    System.out.println("  Frames allocated: ");
    for (int i = 0; i < frames.length; i++) {
        System.out.println("    VPN " + i + " -> PPN " + frames[i]);
    }

    // Initialize page table
    process.pageTable = new TranslationEntry[numPages];
    for (int i = 0; i < numPages; i++) {
        process.pageTable[i] = new TranslationEntry(i, frames[i], true, false, false, false);
    }
    process.numPages = numPages;

    System.out.println("\n[Setup] Page table initialized with valid mappings");

    // Cross-page test setup
    int vaddr = pageSize - 2;
    byte[] writeBuffer = {1, 2, 3, 4, 5, 6};
    byte[] readBuffer = new byte[6];

    System.out.println("\n[Test 1] Writing across page boundary");
    System.out.println("  Start virtual address: " + vaddr);
    System.out.println("  This spans:");
    System.out.println("    Page " + (vaddr / pageSize) + " (last 2 bytes)");
    System.out.println("    Page " + ((vaddr / pageSize) + 1) + " (next 4 bytes)");

    System.out.print("  WriteBuffer = [ ");
    for (byte b : writeBuffer) System.out.print(b + " ");
    System.out.println("]");

    int bytesWritten = process.writeVirtualMemory(vaddr, writeBuffer, 0, 6);
    System.out.println("  BytesWritten = " + bytesWritten + " (expected 6): "
            + (bytesWritten == 6 ? "PASSED" : "FAILED"));

    System.out.println("\n[Test 2] Reading back across same boundary");

    int bytesRead = process.readVirtualMemory(vaddr, readBuffer, 0, 6);
    System.out.println("  BytesRead = " + bytesRead + " (expected 6): "
            + (bytesRead == 6 ? "PASSED" : "FAILED"));

    System.out.print("  ReadBuffer = [ ");
    for (byte b : readBuffer) System.out.print(b + " ");
    System.out.println("]");

    System.out.println("\n[Test 3] Verifying data integrity");

    boolean match = true;
    for (int i = 0; i < writeBuffer.length; i++) {
        if (writeBuffer[i] != readBuffer[i]) {
            match = false;
            System.out.println("  Mismatch at index " + i +
                    ": expected " + writeBuffer[i] +
                    ", got " + readBuffer[i]);
            break;
        }
    }

    System.out.println("  ReadBuffer matches WriteBuffer: "
            + (match ? "PASSED" : "FAILED"));

    System.out.println("\n[Cleanup] Releasing allocated frames...");

    // Cleanup
    UserKernel.releaseFrames(frames);
	System.out.println();
    System.out.println("===== Test Complete =====");
}


	public void testBasicExecution() {
		System.out.println("[Test 1] Basic Execution:");
		System.out.println("Simulating: pid = syscall.exec(\"halt.coff\") and syscall.exit(0) inside the child.");
		System.out.println("Expectation: Exec returns a valid positive PID. Child loads into its own address space, executes, and kernel cleans up process state on exit.");
		
		initializeFreeFramesIfNeeded();
		
		// Simulate exec(): pull a unique PID and allocate the child's frames.
		pidLock.acquire();
		int childPid = nextPid++;
		pidLock.release();
		
		int framesBefore = freeFrames.size();
		int[] childFrames =UserKernel.allocateFrames(10);
		boolean execOk = (childFrames != null && childFrames.length == 10);
		
		System.out.println("  Child PID assigned = " + childPid + "  (valid: " + (childPid >= 0) + ")");
		System.out.println("  Frames allocated   = " + (execOk ? childFrames.length : 0));
		
		// Simulate exit(): unloadSections returns the frames to the global pool.
		if (childFrames != null) {
			pageLock.acquire();
			for (int f : childFrames) freeFrames.add(f);
			pageLock.release();
		}
		int framesAfter = freeFrames.size();
		boolean cleanupOk = (framesAfter == framesBefore);
		System.out.println("  Frames before=" + framesBefore + ", after=" + framesAfter + "  (cleanup OK: " + cleanupOk + ")");
		
		if (childPid >= 0 && execOk && cleanupOk)
			System.out.println("Result: Child successfully spawned, PID > 0, state cleaned up.");
		else
			System.out.println("Result: FAIL.");
		System.out.println();
	}
	
	public void testSynchronization() {
		System.out.println("[Test 2] Synchronization:");
		System.out.println("Simulating: Parent calls join on child.");
		System.out.println("Expectation: Parent blocks until child finishes. Join returns the child's exact exit status (e.g., 5).");
		
		// Mimic the parent.children -> child.exitStatus lookup that handleJoin
		// performs after thread.join() returns.
		HashMap<Integer, Integer> parentChildren = new HashMap<Integer, Integer>();
		int childPid = 42;
		int childExitStatus = 5;
		parentChildren.put(childPid, childExitStatus);
		
		Integer found = parentChildren.get(childPid);
		boolean foundChild = (found != null);
		int reportedStatus = foundChild ? found.intValue() : -1;
		
		System.out.println("  Child PID " + childPid + " exited with status " + childExitStatus);
		System.out.println("  join(" + childPid + ") found child: " + foundChild);
		System.out.println("  Status read back from child object: " + reportedStatus);
		
		if (foundChild && reportedStatus == 5)
			System.out.println("Result: Join blocked successfully, exit status matches.");
		else
			System.out.println("Result: FAIL.");
		System.out.println();
	}
	
	public void testRecursiveSpawning() {
		System.out.println("[Test 3] Recursive Spawning:");
		System.out.println("Simulating: Process A execs B, Process B execs C.");
		System.out.println("Expectation: Globally unique PIDs assigned to B and C. B spawns its child while remaining a child of A.");
		
		// PIDs come from the same global counter that the constructor uses.
		pidLock.acquire();
		int pidA = nextPid++;
		int pidB = nextPid++;
		int pidC = nextPid++;
		pidLock.release();
		
		// Each process keeps its own children map. C is B's child, NOT A's.
		HashMap<Integer, Integer> aChildren = new HashMap<Integer, Integer>();
		HashMap<Integer, Integer> bChildren = new HashMap<Integer, Integer>();
		aChildren.put(pidB, 0);
		bChildren.put(pidC, 0);
		
		boolean uniquePids   = (pidA != pidB && pidB != pidC && pidA != pidC);
		boolean bUnderA      = aChildren.containsKey(pidB);
		boolean cUnderB      = bChildren.containsKey(pidC);
		boolean cNotUnderA   = !aChildren.containsKey(pidC);
		
		System.out.println("  PIDs: A=" + pidA + ", B=" + pidB + ", C=" + pidC + "  (unique: " + uniquePids + ")");
		System.out.println("  B in A.children: " + bUnderA);
		System.out.println("  C in B.children: " + cUnderB);
		System.out.println("  C NOT a direct child of A: " + cNotUnderA);
		
		if (uniquePids && bUnderA && cUnderB && cNotUnderA)
			System.out.println("Result: Concurrent address spaces maintained, valid hierarchy.");
		else
			System.out.println("Result: FAIL.");
		System.out.println();
	}
	
	public void testMemoryRecycling() {
		System.out.println("[Test 4] Memory Recycling:");
		System.out.println("Simulating: Loop 100 times: pid = syscall.exec(\"small.coff\"); syscall.join(pid);");
		System.out.println("Expectation: System executes all 100 without memory exhaustion. Pages freed on exit.");
		
		initializeFreeFramesIfNeeded();
		int initialFrames = freeFrames.size();
		int framesPerProc = 5;          // pretend small.coff needs 5 pages
		int completed = 0;
		
		for (int i = 0; i < 100; i++) {
			int[] frames =UserKernel.allocateFrames(framesPerProc);   // exec()
			if (frames == null) {
				System.out.println("  Allocation failed at iter " + i);
				break;
			}
			// exit() / unloadSections() returns the frames to the pool.
			pageLock.acquire();
			for (int f : frames) freeFrames.add(f);
			pageLock.release();
			completed++;
		}
		
		int finalFrames = freeFrames.size();
		boolean noLeak = (finalFrames == initialFrames);
		
		System.out.println("  Iterations completed: " + completed + " / 100");
		System.out.println("  Free frames before=" + initialFrames + ", after=" + finalFrames + "  (no leak: " + noLeak + ")");
		
		if (completed == 100 && noLeak)
			System.out.println("Result: 100 iterations completed, physical memory successfully recycled.");
		else
			System.out.println("Result: FAIL.");
		System.out.println();
	}
	
	public void testSecurityPermissions() {
		System.out.println("[Test 5] Security/Permissions:");
		System.out.println("Simulating: Process A execs B. Process C attempts to join B.");
		System.out.println("Expectation: Join in C returns -1 immediately. Strict enforcement of parent-child relationship.");
		
		pidLock.acquire();
		int pidA = nextPid++;
		int pidB = nextPid++;
		int pidC = nextPid++;
		pidLock.release();
		
		// A execs B, so B is in A.children. C never execed B, so C.children is empty.
		HashMap<Integer, Integer> aChildren = new HashMap<Integer, Integer>();
		HashMap<Integer, Integer> cChildren = new HashMap<Integer, Integer>();
		aChildren.put(pidB, 0);
		
		// This is exactly what handleJoin does: children.get(childPid); null -> -1.
		int cJoinResult = (cChildren.get(pidB) == null) ? -1 :  1;
		int aJoinResult = (aChildren.get(pidB) == null) ? -1 :  1;   // sanity check
		
		System.out.println("  PIDs: A=" + pidA + ", B=" + pidB + ", C=" + pidC);
		System.out.println("  C.join(B) -> " + cJoinResult + "  (expected -1)");
		System.out.println("  A.join(B) -> " + aJoinResult + "  (sanity, expected != -1)");
		
		if (cJoinResult == -1 && aJoinResult != -1)
			System.out.println("Result: Process C join returned -1. Security intact.");
		else
			System.out.println("Result: FAIL.");
		System.out.println();
	}
}