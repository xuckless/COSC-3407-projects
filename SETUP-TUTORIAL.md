
# Nachos Development Setup & Workflow Guide

This guide covers the environment setup and development workflow for our Nachos projects. We are using **Java 11** to ensure compatibility with the Nachos hardware simulator.

---

## 1. Environment Setup

### Install JDK 11

Nachos was designed for older Java versions. Using modern Java (like 22) will cause "LinkageErrors" and syntax issues with the `yield` keyword.

#### **macOS (Homebrew):**
```bash
brew install openjdk@11

```


* **Path Configuration:**
  Add this to your shell profile (`~/.zshrc` or `~/.bash_profile`) so the terminal always uses the correct version:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@11
export PATH=$JAVA_HOME/bin:$PATH

```

#### **Windows**

1. Download **JDK 11** (e.g., [Amazon Corretto 11](https://aws.amazon.com/corretto/) or [Eclipse Temurin](https://adoptium.net/)).
2. Run the installer and note the installation path.
3. **Environment Variables:**
* Search for "Edit the system environment variables" in Windows Search.
* Click **Environment Variables**.
* Under **System Variables**, click **New**. Variable name: `JAVA_HOME`, Variable value: `C:\Path\To\Your\JDK11`.
* Find the **Path** variable in System Variables, click **Edit**, then **New**, and add `%JAVA_HOME%\bin`.



### Add Nachos to PATH

To run the `nachos` command from anywhere inside a project folder:

#### **MacOS:**
1. Locate your `nachos/bin` directory.
2. Add it to your path in your shell profile:
```bash
export PATH="path/to/your/nachos/bin:$PATH"

```

#### **Windows:**
1.  In the **Environment Variables** window, edit the **Path** variable and add the full path to your `nachos\bin` folder.




---

## 2. Project Structure

The root directory contains the source code folders and the project build folders:

### **Source Code Folders:**
* `threads/`: Code for Project 1 (Thread management, Sync, Alarm).
* `userprog/`: Code for Project 2 (System calls, Multiprogramming).
* `vm/`: Code for Project 3 (Virtual Memory).
* `network/`: Code for Project 4 (Networking).
* `machine/`: **DO NOT TOUCH.** This simulates the MIPS hardware.


### **Build Folders (`proj1`, `proj2`, etc.):**
* These contain the `Makefile` and `nachos.conf`.
* You run your commands inside these folders.
* Build folders only contain compiled java code.



---

## 3. The Workflow (Terminal Based)

Even if you use IntelliJ or VS Code to edit the text, the **Terminal** is the source of truth for building and running.

### Step 1: Modify Code

Edit the `.java` files in the root source folders (e.g., `~/nachos/threads/KThread.java`).

### Step 2: Compile

Navigate to the directory of the project you are working on and use `make`:

```bash
cd ~/nachos/proj1
make

```

*Note: This takes the source code from the root and puts compiled `.class` files into a `nachos` folder inside `proj1`.*

### Step 3: Run

Execute the simulator:

```bash
nachos

```

### Step 4: Debugging Flags

Use flags to see what the kernel is doing:

* `nachos -d t`: Shows thread debugging info (context switches, etc).
* `nachos -s 1`: Runs the autograder self-tests for Project 1.

---

## 4. Important Rules

* **Don't Touch the Machine Folder:** The `machine/` directory is the simulated hardware. Modifying this will break the simulation and your code won't work on the grader's machine.
* **Clean Before Rebuilding:** If you get weird errors or "LinkageErrors," wipe the old compiled files and start fresh:
```bash
cd proj1
make clean
make

```


* **Working Directory:** Always run the `nachos` command from within a `projX` folder. It needs to find the `nachos.conf` file in that specific directory to boot correctly.

---

## 5. Summary of Projects

1. **Project 1 (Threads):** Implementing `join()`, `Alarm.waitUntil()`, and condition variables.
2. **Project 2 (User Programs):** Handling system calls (read, write, exit) and memory mapping.

---


