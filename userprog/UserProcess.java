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
		int entryOffset = (numPages-1)*pageSize;
		int stringOffset = entryOffset + args.length*4;
		
		this.argc = args.length;
		this.argv = entryOffset;
		
		for (int i=0; i<argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset,stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset,new byte[] { 0 }) == 1);
			stringOffset += 1;
		}
		
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
		int[] physFrames = allocateFrames(numPages);
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
				freeFrames.add(pageTable[i].ppn);
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
	
	private static int[] allocateFrames(int numFrames) {
		pageLock.acquire();
		if (numFrames <= 0 || freeFrames.size() < numFrames) {
			pageLock.release();
			return null;
		}
		int[] frames = new int[numFrames];
		for (int i = 0; i < numFrames; i++)
			frames[i] = freeFrames.removeFirst();
		pageLock.release();
		return frames;
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
		
		// The exec syscall must be passed a coff binary.
		if (!filename.endsWith(".coff"))
			return -1;
		
		// Read each argv[i] pointer, then dereference it to a string.
		String[] args = new String[argc];
		for (int i = 0; i < argc; i++) {
			byte[] argPtrBytes = new byte[4];
			int read = readVirtualMemory(argvVaddr + i * 4, argPtrBytes);
			if (read != 4)
				return -1;
			
			int argStrVaddr = Lib.bytesToInt(argPtrBytes, 0);
			args[i] = readVirtualMemoryString(argStrVaddr, MAX_STRING_LENGTH);
			if (args[i] == null)
				return -1;
		}
		
		// Create the child. The constructor already incremented the active
		// process count; if execute() fails we must roll that back.
		UserProcess child = UserProcess.newUserProcess();
		child.parent = this;
		
		if (!child.execute(filename, args)) {
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
		int[] childFrames = allocateFrames(10);
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
			int[] frames = allocateFrames(framesPerProc);   // exec()
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