
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif

#include <winsock2.h>
#include <windows.h>

#include <psapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <tlhelp32.h>


#define SERVER_PORT 7946
#define CLIENT_PORT 7947
#define BUFFER_SIZE 64

#define RC_EXIT 0
#define RC_INIT 1
#define RC_EXEC 2
#define RC_KILL_PROCESS 3
#define RC_LIST_PROCESSES 4
#define RC_GET_PROCESS 5
#define RC_SET_PROCESS_AFFINITY 6
#define RC_MOUSE_EVENT 7
#define RC_GET_GAMEPAD                                                         \
  8
#define RC_GET_GAMEPAD_STATE                                                   \
  9
#define RC_RELEASE_GAMEPAD                                                     \
  10
#define RC_KEYBOARD_EVENT 11
#define RC_BRING_TO_FRONT 12
#define RC_CURSOR_POS_FEEDBACK 13
#define RC_TEXT_INPUT_MONITOR 14
#define RC_TEXT_INPUT_STATE 15

#define TEXT_INPUT_POLL_INTERVAL 100
#define MIN_CARET_HEIGHT 4
#define MAX_IME_MANAGED_WINDOWS 64

#define TEXT_INPUT_INACTIVE 0
#define TEXT_INPUT_ACTIVE 1
#define TEXT_INPUT_BLOCKED 2

#pragma pack(push, 1)
typedef struct {
  BYTE code;
  DWORD padding;
  WORD numProcesses;
  WORD index;
  DWORD pid;
  DWORD64 memory;
  DWORD affinity;
  BYTE isWow64;
  char name[32];
} ProcessPacket;

typedef struct {
  BYTE code;
  BYTE state;
  DWORD hwnd;
  short left;
  short top;
  short right;
  short bottom;
} TextInputPacket;
#pragma pack(pop)

SOCKET sock;
struct sockaddr_in clientAddr;
int clientAddrLen = sizeof(clientAddr);
volatile BOOL running = TRUE;
volatile BOOL textInputMonitorEnabled = FALSE;
volatile BOOL textInputForceSend = FALSE;

typedef struct PidNode {
  DWORD pid;
  struct PidNode *next;
} PidNode;

PidNode *seenPidsHead = NULL;

void addSeenPid(DWORD pid) {
  PidNode *newNode = (PidNode *)malloc(sizeof(PidNode));
  newNode->pid = pid;
  newNode->next = seenPidsHead;
  seenPidsHead = newNode;
}

BOOL isPidSeen(DWORD pid) {
  PidNode *current = seenPidsHead;
  while (current) {
    if (current->pid == pid)
      return TRUE;
    current = current->next;
  }
  return FALSE;
}

void sendCursorFeedback() {
  POINT ptr;
  if (GetCursorPos(&ptr)) {
    BYTE buffer[5];
    buffer[0] = RC_CURSOR_POS_FEEDBACK;
    *(short *)(buffer + 1) = (short)ptr.x;
    *(short *)(buffer + 3) = (short)ptr.y;
    sendto(sock, (const char *)buffer, 5, 0, (struct sockaddr *)&clientAddr,
           clientAddrLen);
  }
}

void handleListProcesses() {
  HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (hSnap == INVALID_HANDLE_VALUE)
    return;

  PROCESSENTRY32 pe32;
  pe32.dwSize = sizeof(PROCESSENTRY32);

  int count = 0;
  if (Process32First(hSnap, &pe32)) {
    do {
      count++;
    } while (Process32Next(hSnap, &pe32));
  }

  if (count == 0) {
    CloseHandle(hSnap);
    return;
  }

  Process32First(hSnap, &pe32);
  int index = 0;
  do {

    ProcessPacket packet;
    ZeroMemory(&packet, sizeof(packet));
    packet.code = RC_GET_PROCESS;
    packet.numProcesses = (WORD)count;
    packet.index = (WORD)index;
    packet.pid = pe32.th32ProcessID;
    strncpy(packet.name, pe32.szExeFile, 31);
    packet.name[31] = '\0';

    HANDLE hProcess = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ,
                                  FALSE, pe32.th32ProcessID);
    if (hProcess) {
      PROCESS_MEMORY_COUNTERS pmc;
      if (GetProcessMemoryInfo(hProcess, &pmc, sizeof(pmc))) {
        packet.memory = pmc.WorkingSetSize;
      }

      DWORD_PTR procAffinity, sysAffinity;
      if (GetProcessAffinityMask(hProcess, &procAffinity, &sysAffinity)) {
        packet.affinity = (DWORD)procAffinity;
      }

      BOOL isWow64 = FALSE;
      IsWow64Process(hProcess, &isWow64);
      packet.isWow64 = (BYTE)isWow64;

      CloseHandle(hProcess);
    }

    sendto(sock, (const char *)&packet, sizeof(packet), 0,
           (struct sockaddr *)&clientAddr, clientAddrLen);
    index++;
  } while (Process32Next(hSnap, &pe32) && index < count);

  CloseHandle(hSnap);
}

void handleExec(const char *payload, int len) {

  if (len < 8)
    return;
  int filenameLen = *(int *)(payload);
  int paramsLen = *(int *)(payload + 4);

  if (len < 8 + filenameLen)
    return;

  char *filename = (char *)malloc(filenameLen + 1);
  char *params = (char *)malloc(paramsLen + 1);

  if (filename) {
    memcpy(filename, payload + 8, filenameLen);
    filename[filenameLen] = '\0';
  }

  if (params) {
    memcpy(params, payload + 8 + filenameLen, paramsLen);
    params[paramsLen] = '\0';
  }

  ShellExecuteA(NULL, "open", filename, params, NULL, SW_SHOW);

  if (filename)
    free(filename);
  if (params)
    free(params);
}

void handleKillProcess(const char *payload, int len) {

  int nameLen = *(int *)payload;
  char *name = (char *)malloc(nameLen + 1);
  memcpy(name, payload + 4, nameLen);
  name[nameLen] = '\0';

  HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (hSnap != INVALID_HANDLE_VALUE) {
    PROCESSENTRY32 pe32;
    pe32.dwSize = sizeof(PROCESSENTRY32);
    if (Process32First(hSnap, &pe32)) {
      do {
        if (_stricmp(pe32.szExeFile, name) == 0) {
          HANDLE hProcess =
              OpenProcess(PROCESS_TERMINATE, FALSE, pe32.th32ProcessID);
          if (hProcess) {
            TerminateProcess(hProcess, 1);
            CloseHandle(hProcess);
          }
        }
      } while (Process32Next(hSnap, &pe32));
    }
    CloseHandle(hSnap);
  }
  free(name);
}

void handleSetAffinity(const char *buffer, int len) {

  int pid = *(int *)(buffer + 5);
  int mask = *(int *)(buffer + 9);

  if (pid != 0) {
    HANDLE hProcess = OpenProcess(PROCESS_SET_INFORMATION, FALSE, pid);
    if (hProcess) {
      SetProcessAffinityMask(hProcess, mask);
      CloseHandle(hProcess);
    }
  } else {
    int nameLen = (unsigned char)buffer[13];
    char *name = (char *)malloc(nameLen + 1);
    memcpy(name, buffer + 14, nameLen);
    name[nameLen] = '\0';

    HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnap != INVALID_HANDLE_VALUE) {
      PROCESSENTRY32 pe32;
      pe32.dwSize = sizeof(PROCESSENTRY32);
      if (Process32First(hSnap, &pe32)) {
        do {
          if (_stricmp(pe32.szExeFile, name) == 0) {
            HANDLE hProcess =
                OpenProcess(PROCESS_SET_INFORMATION, FALSE, pe32.th32ProcessID);
            if (hProcess) {
              SetProcessAffinityMask(hProcess, mask);
              CloseHandle(hProcess);
            }
          }
        } while (Process32Next(hSnap, &pe32));
      }
      CloseHandle(hSnap);
    }
    free(name);
  }
}

struct FindWindowData {
  DWORD pid;
  HWND hWnd;
};

BOOL CALLBACK EnumWindowsProc(HWND hWnd, LPARAM lParam) {
  struct FindWindowData *data = (struct FindWindowData *)lParam;
  DWORD currPid = 0;
  GetWindowThreadProcessId(hWnd, &currPid);
  if (currPid == data->pid && IsWindowVisible(hWnd) &&
      GetWindow(hWnd, GW_OWNER) == NULL) {
    data->hWnd = hWnd;
    return FALSE;
  }
  return TRUE;
}

void handleBringToFront(const char *buffer, int len) {
  int nameLen = *(int *)(buffer + 1);
  char *name = (char *)malloc(nameLen + 1);
  memcpy(name, buffer + 5, nameLen);
  name[nameLen] = '\0';

  DWORD pid = 0;
  HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (hSnap != INVALID_HANDLE_VALUE) {
    PROCESSENTRY32 pe32;
    pe32.dwSize = sizeof(PROCESSENTRY32);
    if (Process32First(hSnap, &pe32)) {
      do {
        if (_stricmp(pe32.szExeFile, name) == 0) {
          pid = pe32.th32ProcessID;
          break;
        }
      } while (Process32Next(hSnap, &pe32));
    }
    CloseHandle(hSnap);
  }

  if (pid != 0) {
    struct FindWindowData winData = {pid, NULL};
    EnumWindows(EnumWindowsProc, (LPARAM)&winData);
    if (winData.hWnd) {
      if (IsIconic(winData.hWnd))
        ShowWindow(winData.hWnd, SW_RESTORE);
      else
        ShowWindow(winData.hWnd, SW_SHOW);
      SetForegroundWindow(winData.hWnd);
    }
  }

  free(name);
}

void handleInput(const char *buffer, int len) {
  BYTE code = buffer[0];
  if (code == RC_MOUSE_EVENT) {

    int flags = *(int *)(buffer + 5);
    short dx = *(short *)(buffer + 9);
    short dy = *(short *)(buffer + 11);
    short wheel = *(short *)(buffer + 13);
    BYTE feedback = (BYTE)buffer[15];

    mouse_event(flags, dx, dy, wheel, 0);
    if (feedback)
      sendCursorFeedback();
  } else if (code == RC_KEYBOARD_EVENT) {

    BYTE vkey = (BYTE)buffer[1];
    int flags = *(int *)(buffer + 2);
    keybd_event(vkey, 0, flags, 0);
  }
}

static BOOL containsIgnoreCase(const char *haystack, const char *needle) {
  size_t needleLen = strlen(needle);
  for (; *haystack; haystack++) {
    if (_strnicmp(haystack, needle, needleLen) == 0)
      return TRUE;
  }
  return FALSE;
}

/* Standard text controls and their common superclasses: Edit, RichEdit*,
 * WindowsForms10.EDIT.*, TEdit/TMemo (VCL), ThunderTextBox (VB), Scintilla. */
static BOOL isTextInputClass(HWND hWnd) {
  char className[128];
  if (!GetClassNameA(hWnd, className, sizeof(className)))
    return FALSE;
  return containsIgnoreCase(className, "edit") ||
         containsIgnoreCase(className, "textbox") ||
         containsIgnoreCase(className, "memo") ||
         containsIgnoreCase(className, "scintilla");
}

/* AWT keeps the input context enabled on every focusable Swing component, so
 * its IME state only tells read-only text apart. */
static BOOL isAwtWindow(HWND hWnd) {
  char className[16];
  return GetClassNameA(hWnd, className, sizeof(className)) &&
         strncmp(className, "SunAwt", 6) == 0;
}

static BOOL CALLBACK findImeUiWindow(HWND hWnd, LPARAM lParam) {
  char className[16];
  if (GetClassNameA(hWnd, className, sizeof(className)) &&
      strcmp(className, "Wine IME") == 0) {
    *(HWND *)lParam = hWnd;
    return FALSE;
  }
  return TRUE;
}

/* Wine's builtin IME mirrors the input context of the focused window in its
 * UI window (IMMGWL_IMC), and clears it when the application disassociates
 * the context. Returns -1 when the thread has no IME UI window yet. */
static int getImeContextState(DWORD threadId) {
  HWND uiWindow = NULL;
  EnumThreadWindows(threadId, findImeUiWindow, (LPARAM)&uiWindow);
  if (!uiWindow)
    return -1;
  return GetWindowLongPtrW(uiWindow, 0) != 0;
}

/* Windows seen with the IME disabled while focused. Those applications
 * (browsers, Qt, game engines, SDL with text input stopped) enable the IME
 * only while a text field has focus. Everything else keeps the default input
 * context on every window, which says nothing. */
static HWND imeManagedWindows[MAX_IME_MANAGED_WINDOWS];
static int imeManagedWindowsNext = 0;

static BOOL isImeManagedWindow(HWND hWnd) {
  for (int i = 0; i < MAX_IME_MANAGED_WINDOWS; i++) {
    if (imeManagedWindows[i] == hWnd)
      return TRUE;
  }
  return FALSE;
}

static void markImeManagedWindow(HWND hWnd) {
  if (isImeManagedWindow(hWnd))
    return;
  imeManagedWindows[imeManagedWindowsNext] = hWnd;
  imeManagedWindowsNext = (imeManagedWindowsNext + 1) % MAX_IME_MANAGED_WINDOWS;
}

/* Classifies the keyboard focus of the foreground thread. On ACTIVE, target is
 * the text input and rect the screen area a tap must hit to target it.
 * BLOCKED means the focus is known not to accept text (read-only control, or
 * IME disabled by the application), which vetoes the cursor-based detection
 * done on the Java side. Besides the standard controls, any window holding a
 * text-sized system caret counts: that is how custom editors (consoles,
 * Scintilla-likes, some toolkits) expose their insertion point. Tiny carets
 * are ignored, since toolkits like Tk keep a hidden 1x1 caret around just to
 * place the IME window. */
static int queryTextInput(HWND *target, RECT *rect) {
  HWND foreground = GetForegroundWindow();
  DWORD threadId =
      foreground ? GetWindowThreadProcessId(foreground, NULL) : 0;

  GUITHREADINFO info;
  ZeroMemory(&info, sizeof(info));
  info.cbSize = sizeof(info);
  if (!GetGUIThreadInfo(threadId, &info) || !info.hwndFocus)
    return TEXT_INPUT_INACTIVE;

  HWND focus = info.hwndFocus;
  if (!IsWindowEnabled(focus))
    return TEXT_INPUT_INACTIVE;

  if (isTextInputClass(focus)) {
    if (GetWindowLongA(focus, GWL_STYLE) & ES_READONLY)
      return TEXT_INPUT_BLOCKED;
    if (!GetWindowRect(focus, rect))
      return TEXT_INPUT_INACTIVE;
    *target = focus;
    return TEXT_INPUT_ACTIVE;
  }

  int caretHeight = info.rcCaret.bottom - info.rcCaret.top;
  if (info.hwndCaret && caretHeight >= MIN_CARET_HEIGHT &&
      GetWindowRect(info.hwndCaret, rect)) {
    /* The caret window may be a whole page or canvas: narrow the target down
     * to the band around the line being edited. */
    RECT caret = info.rcCaret;
    MapWindowPoints(info.hwndCaret, NULL, (POINT *)&caret, 2);
    rect->top = max(rect->top, caret.top - caretHeight);
    rect->bottom = min(rect->bottom, caret.bottom + caretHeight);
    *target = info.hwndCaret;
    return TEXT_INPUT_ACTIVE;
  }

  int imeState = getImeContextState(threadId);
  if (imeState == 0) {
    markImeManagedWindow(focus);
    return TEXT_INPUT_BLOCKED;
  }
  if (imeState == 1 && isImeManagedWindow(focus) && !isAwtWindow(focus) &&
      GetWindowRect(focus, rect)) {
    *target = focus;
    return TEXT_INPUT_ACTIVE;
  }
  return TEXT_INPUT_INACTIVE;
}

static void sendTextInputState(int state, HWND hWnd, const RECT *rect) {
  TextInputPacket packet;
  ZeroMemory(&packet, sizeof(packet));
  packet.code = RC_TEXT_INPUT_STATE;
  packet.state = (BYTE)state;
  packet.hwnd = (DWORD)(ULONG_PTR)hWnd;
  if (state == TEXT_INPUT_ACTIVE) {
    packet.left = (short)rect->left;
    packet.top = (short)rect->top;
    packet.right = (short)rect->right;
    packet.bottom = (short)rect->bottom;
  }
  sendto(sock, (const char *)&packet, sizeof(packet), 0,
         (struct sockaddr *)&clientAddr, clientAddrLen);
}

DWORD WINAPI TextInputMonitorThread(LPVOID lpParam) {
  /* Report physical coordinates so they match the X server's, whatever DPI
   * the prefix uses. Only this thread changes awareness. */
  typedef HANDLE(WINAPI * SetThreadDpiAwarenessContextFn)(HANDLE);
  SetThreadDpiAwarenessContextFn setThreadDpiAwarenessContext =
      (SetThreadDpiAwarenessContextFn)GetProcAddress(
          GetModuleHandleA("user32.dll"), "SetThreadDpiAwarenessContext");
  if (setThreadDpiAwarenessContext)
    setThreadDpiAwarenessContext((HANDLE)-3); /* PER_MONITOR_AWARE */

  int lastState = TEXT_INPUT_INACTIVE;
  HWND lastWnd = NULL;
  RECT lastRect = {0};

  while (running) {
    if (textInputMonitorEnabled) {
      RECT rect = {0};
      HWND hWnd = NULL;
      int state = queryTextInput(&hWnd, &rect);

      if (textInputForceSend || state != lastState || hWnd != lastWnd ||
          !EqualRect(&rect, &lastRect)) {
        textInputForceSend = FALSE;
        sendTextInputState(state, hWnd, &rect);
        lastState = state;
        lastWnd = hWnd;
        lastRect = rect;
      }
    } else {
      lastState = TEXT_INPUT_INACTIVE;
      lastWnd = NULL;
    }
    Sleep(TEXT_INPUT_POLL_INTERVAL);
  }
  return 0;
}

DWORD WINAPI ServerThread(LPVOID lpParam) {
  WSADATA wsaData;
  if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0)
    return 0;

  sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (sock == INVALID_SOCKET) {
    WSACleanup();
    return 0;
  }

  struct sockaddr_in serverAddr;
  serverAddr.sin_family = AF_INET;
  serverAddr.sin_port = htons(SERVER_PORT);
  serverAddr.sin_addr.s_addr = inet_addr("127.0.0.1");

  if (bind(sock, (struct sockaddr *)&serverAddr, sizeof(serverAddr)) ==
      SOCKET_ERROR) {
    closesocket(sock);
    WSACleanup();
    return 0;
  }

  clientAddr.sin_family = AF_INET;
  clientAddr.sin_port = htons(CLIENT_PORT);
  clientAddr.sin_addr.s_addr = inet_addr("127.0.0.1");

  char initBuffer[64];
  initBuffer[0] = RC_INIT;
  sendto(sock, initBuffer, 1, 0, (struct sockaddr *)&clientAddr, clientAddrLen);

  char buffer[BUFFER_SIZE];
  while (running) {
    struct sockaddr_in sender;
    int senderLen = sizeof(sender);
    int len = recvfrom(sock, buffer, BUFFER_SIZE, 0, (struct sockaddr *)&sender,
                       &senderLen);

    if (len > 0) {
      BYTE command = buffer[0];

      switch (command) {
      case RC_EXIT:
        running = FALSE;
        ExitProcess(0);
        break;
      case RC_INIT:
        break;
      case RC_EXEC:
        handleExec(buffer + 5, len - 5);
      case RC_KILL_PROCESS:
        handleKillProcess(buffer + 1, len - 1);
        break;
      case RC_LIST_PROCESSES:
        handleListProcesses();
        break;
      case RC_SET_PROCESS_AFFINITY:
        handleSetAffinity(buffer, len);
        break;
      case RC_MOUSE_EVENT:
      case RC_KEYBOARD_EVENT:
        handleInput(buffer, len);
        break;
      case RC_BRING_TO_FRONT:
        handleBringToFront(buffer, len);
        break;
      case RC_TEXT_INPUT_MONITOR:
        if (len >= 2) {
          textInputMonitorEnabled = buffer[1] != 0;
          textInputForceSend = TRUE;
        }
        break;
      }
    }
  }

  closesocket(sock);
  WSACleanup();
  return 0;
}

void handleChildProcesses(int affinityMask) {
  if (affinityMask <= 0)
    return;

  DWORD myPid = GetCurrentProcessId();

  while (running) {
    HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnap != INVALID_HANDLE_VALUE) {
      PROCESSENTRY32 pe32;
      pe32.dwSize = sizeof(PROCESSENTRY32);
      if (Process32First(hSnap, &pe32)) {
        do {
          if (pe32.th32ProcessID > myPid) {
            if (!isPidSeen(pe32.th32ProcessID)) {
              HANDLE hProcess = OpenProcess(PROCESS_SET_INFORMATION, FALSE,
                                            pe32.th32ProcessID);
              if (hProcess) {
                SetProcessAffinityMask(hProcess, affinityMask);
                CloseHandle(hProcess);
              }
              addSeenPid(pe32.th32ProcessID);
            }
          }
        } while (Process32Next(hSnap, &pe32));
      }
      CloseHandle(hSnap);
    }
    Sleep(1000);
  }
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                   LPSTR lpCmdLine, int nCmdShow) {
  int affinity = 0;
  char *directory = NULL;
  char *executable = "wfm.exe";
  char *params = NULL;

  int argc = __argc;
  char **argv = __argv;

  int argIdx = 1;
  while (argIdx < argc) {
    if (strcmp(argv[argIdx], "/affinity") == 0) {
      if (argIdx + 1 < argc) {
        affinity = (int)strtol(argv[argIdx + 1], NULL, 16);
        argIdx += 2;
      } else {
        argIdx++;
      }
    } else if (strcmp(argv[argIdx], "/dir") == 0) {
      if (argIdx + 1 < argc) {
        directory = argv[argIdx + 1];
        argIdx += 2;
      } else {
        argIdx++;
      }
    } else {
      executable = argv[argIdx];
      argIdx++;
      break;
    }
  }

  char execArgs[2048] = {0};
  if (argIdx < argc) {
    for (int i = argIdx; i < argc; i++) {
      strcat(execArgs, "\"");
      strcat(execArgs, argv[i]);
      strcat(execArgs, "\" ");
    }
  }

  SHELLEXECUTEINFOA sei = {0};
  sei.cbSize = sizeof(SHELLEXECUTEINFOA);
  sei.fMask = SEE_MASK_NOCLOSEPROCESS;
  sei.lpFile = executable;
  sei.lpParameters = execArgs[0] ? execArgs : NULL;
  sei.lpDirectory = directory;
  sei.nShow = SW_SHOW;

  ShellExecuteExA(&sei);
  if (sei.hProcess)
    CloseHandle(sei.hProcess);

  HANDLE hThread = CreateThread(NULL, 0, ServerThread, NULL, 0, NULL);
  HANDLE hTextInputThread =
      CreateThread(NULL, 0, TextInputMonitorThread, NULL, 0, NULL);
  if (hTextInputThread)
    CloseHandle(hTextInputThread);

  if (affinity > 0) {
    handleChildProcesses(affinity);
  } else {
    WaitForSingleObject(hThread, INFINITE);
  }

  return 0;
}
