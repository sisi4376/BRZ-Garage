#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <windowsx.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "lvgl.h"
#include "export_path/ui.h"
#include "export_path/ui_ext.h"
#include "sim_ui_port.h"
#include "bsp_obd_dsp/nvs_storage.h"

#define SIM_W 466
#define SIM_H 466

typedef struct {
    int rpm;
    int speed;
    int coolant;
    int intake;
    int oil;
    int load;
    int throttle;
    int gear;
    int oil_pressure;
    int maf_x100;
} gauge_data_t;

static HWND g_window;
static uint32_t *g_framebuffer;
static BITMAPINFO g_bitmap_info;
static bool g_running = true;
static bool g_headless = false;
static SOCKET g_elm = INVALID_SOCKET;
static bool g_elm_online = false;
static bool g_force_demo = false;
static bool g_manual_data = false;
static char g_elm_host[64] = "127.0.0.1";
static int g_elm_port = 35000;
static DWORD g_last_elm_retry;
static gauge_data_t g_data = { 900, 0, 82, 31, 74, 18, 4, 0, 165, 320 };

static void show_page(int page)
{
    sim_ui_show_page(page);
}

static void save_framebuffer(void)
{
    const char *temporary = "preview/lvgl_frame.tmp";
    const char *final = "preview/lvgl_frame.bmp";
    FILE *file = fopen(temporary, "wb");
    if (!file) return;

    BITMAPFILEHEADER file_header = {0};
    file_header.bfType = 0x4D42;
    file_header.bfOffBits = sizeof(BITMAPFILEHEADER) + sizeof(BITMAPINFOHEADER);
    file_header.bfSize = file_header.bfOffBits + SIM_W * SIM_H * 4;
    fwrite(&file_header, sizeof(file_header), 1, file);
    fwrite(&g_bitmap_info.bmiHeader, sizeof(g_bitmap_info.bmiHeader), 1, file);
    fwrite(g_framebuffer, SIM_W * SIM_H * 4, 1, file);
    fclose(file);
    MoveFileExA(temporary, final, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
}

static void save_status(void)
{
    const char *temporary = "preview/lvgl_status.tmp";
    const char *final = "preview/lvgl_status.json";
    FILE *file = fopen(temporary, "wb");
    if (!file) return;
    fprintf(file,
            "{\"online\":true,\"source\":\"%s\",\"page\":%d,\"pageName\":\"%s\","
            "\"rpm\":%d,\"speed\":%d,\"coolant\":%d,\"intake\":%d,"
            "\"oil\":%d,\"load\":%d,\"throttle\":%d,\"gear\":%d,\"maf\":%.2f}",
            g_elm_online ? "elm327" : "demo", sim_ui_current_page(),
            sim_ui_page_name(sim_ui_current_page()),
            g_data.rpm, g_data.speed, g_data.coolant, g_data.intake,
            g_data.oil, g_data.load, g_data.throttle, g_data.gear,
            g_data.maf_x100 / 100.0);
    fclose(file);
    MoveFileExA(temporary, final, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
}

static void read_page_command(void)
{
    FILE *file = fopen("preview/lvgl_command.txt", "rb");
    if (!file) return;
    char command[64] = {0};
    fgets(command, sizeof(command), file);
    fclose(file);
    DeleteFileA("preview/lvgl_command.txt");
    int page = -1;
    int rpm = 0;
    int speed = 0;
    int gear = 0;
    if (sscanf(command, "page %d", &page) == 1) {
        show_page(page);
    } else if (sscanf(command, "rpmwarn %d", &page) == 1) {
        sim_ui_set_rpm_warn_threshold((uint16_t)page);
    } else if (sscanf(command, "data %d %d %d", &rpm, &speed, &gear) == 3) {
        if (rpm >= 0 && rpm <= RPM_DIAL_MAX && speed >= 0 && speed <= SPEED_DIAL_MAX &&
            gear >= 0 && gear <= 6) {
            g_data.rpm = rpm;
            g_data.speed = speed;
            g_data.gear = gear;
            g_manual_data = true;
        }
    } else if (strncmp(command, "data-auto", 9) == 0) {
        g_manual_data = false;
    } else if (strncmp(command, "gesture ", 8) == 0) {
        char *direction = command + 8;
        direction[strcspn(direction, "\r\n")] = '\0';
        sim_ui_handle_gesture(direction);
    }
}

static LRESULT CALLBACK window_proc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam)
{
    switch (msg) {
    case WM_CLOSE:
        g_running = false;
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        g_running = false;
        PostQuitMessage(0);
        return 0;
    case WM_ERASEBKGND:
        return 1;
    case WM_KEYDOWN:
        if (wparam == VK_ESCAPE) {
            g_running = false;
            DestroyWindow(hwnd);
        } else if (wparam == VK_LEFT) {
            sim_ui_handle_gesture("right");
        } else if (wparam == VK_RIGHT) {
            sim_ui_handle_gesture("left");
        }
        return 0;
    case WM_LBUTTONDOWN: {
        RECT rect;
        GetClientRect(hwnd, &rect);
        sim_ui_handle_gesture(GET_X_LPARAM(lparam) < (rect.right - rect.left) / 2 ? "right" : "left");
        return 0;
    }
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(hwnd, &ps);
        RECT rect;
        GetClientRect(hwnd, &rect);
        StretchDIBits(dc, 0, 0, rect.right, rect.bottom,
                      0, 0, SIM_W, SIM_H, g_framebuffer, &g_bitmap_info,
                      DIB_RGB_COLORS, SRCCOPY);
        EndPaint(hwnd, &ps);
        return 0;
    }
    }
    return DefWindowProc(hwnd, msg, wparam, lparam);
}

static bool create_window(HINSTANCE instance)
{
    WNDCLASSW wc = {0};
    wc.lpfnWndProc = window_proc;
    wc.hInstance = instance;
    wc.lpszClassName = L"BRZLVGLNativeSimulator";
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    if (!RegisterClassW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return false;

    RECT wanted = {0, 0, SIM_W, SIM_H};
    AdjustWindowRect(&wanted, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, FALSE);
    g_window = CreateWindowW(
        wc.lpszClassName,
        L"BRZ OBD Gauge - native LVGL 8.4 simulator",
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        CW_USEDEFAULT, CW_USEDEFAULT,
        wanted.right - wanted.left, wanted.bottom - wanted.top,
        NULL, NULL, instance, NULL);
    if (!g_window) return false;

    ShowWindow(g_window, SW_SHOW);
    UpdateWindow(g_window);
    return true;
}

static void display_flush(lv_disp_drv_t *drv, const lv_area_t *area, lv_color_t *colors)
{
    int x1 = area->x1 < 0 ? 0 : area->x1;
    int y1 = area->y1 < 0 ? 0 : area->y1;
    int x2 = area->x2 >= SIM_W ? SIM_W - 1 : area->x2;
    int y2 = area->y2 >= SIM_H ? SIM_H - 1 : area->y2;
    int source_width = area->x2 - area->x1 + 1;

    for (int y = y1; y <= y2; ++y) {
        for (int x = x1; x <= x2; ++x) {
            lv_color_t c = colors[(y - area->y1) * source_width + (x - area->x1)];
            lv_color32_t c32;
            c32.full = lv_color_to32(c);
            g_framebuffer[(SIM_H - 1 - y) * SIM_W + x] =
                0xFF000000u | ((uint32_t)c32.ch.red << 16) |
                ((uint32_t)c32.ch.green << 8) | c32.ch.blue;
        }
    }
    if (g_window) InvalidateRect(g_window, NULL, FALSE);
    lv_disp_flush_ready(drv);
}

static void pointer_read(lv_indev_drv_t *drv, lv_indev_data_t *data)
{
    LV_UNUSED(drv);
    POINT point;
    GetCursorPos(&point);
    ScreenToClient(g_window, &point);
    data->point.x = (lv_coord_t)point.x;
    data->point.y = (lv_coord_t)point.y;
    data->state = (GetAsyncKeyState(VK_LBUTTON) & 0x8000) ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
}

static void elm_disconnect(void)
{
    if (g_elm != INVALID_SOCKET) closesocket(g_elm);
    g_elm = INVALID_SOCKET;
    g_elm_online = false;
    sim_ui_set_elm_connected(false);
}

static bool socket_wait(SOCKET sock, bool write_ready, int timeout_ms)
{
    fd_set set;
    FD_ZERO(&set);
    FD_SET(sock, &set);
    struct timeval timeout = { timeout_ms / 1000, (timeout_ms % 1000) * 1000 };
    return select(0, write_ready ? NULL : &set, write_ready ? &set : NULL, NULL, &timeout) > 0;
}

static bool elm_read_prompt(char *output, size_t capacity, int timeout_ms)
{
    size_t used = 0;
    DWORD start = GetTickCount();
    while (GetTickCount() - start < (DWORD)timeout_ms && used + 1 < capacity) {
        if (!socket_wait(g_elm, false, 50)) continue;
        int count = recv(g_elm, output + used, (int)(capacity - used - 1), 0);
        if (count <= 0) return false;
        used += (size_t)count;
        output[used] = '\0';
        if (strchr(output, '>')) return true;
    }
    return false;
}

static bool elm_command(const char *command, char *response, size_t capacity, int timeout_ms)
{
    char wire[32];
    snprintf(wire, sizeof(wire), "%s\r", command);
    if (send(g_elm, wire, (int)strlen(wire), 0) <= 0) return false;
    response[0] = '\0';
    return elm_read_prompt(response, capacity, timeout_ms);
}

static bool elm_connect(void)
{
    elm_disconnect();
    g_elm = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (g_elm == INVALID_SOCKET) return false;

    u_long nonblocking = 1;
    ioctlsocket(g_elm, FIONBIO, &nonblocking);
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_port = htons((u_short)g_elm_port);
    address.sin_addr.s_addr = inet_addr(g_elm_host);
    int result = connect(g_elm, (struct sockaddr *)&address, sizeof(address));
    if (result == SOCKET_ERROR && WSAGetLastError() != WSAEWOULDBLOCK) {
        elm_disconnect();
        return false;
    }
    if (!socket_wait(g_elm, true, 200)) {
        elm_disconnect();
        return false;
    }
    int socket_error = 0;
    int error_length = sizeof(socket_error);
    getsockopt(g_elm, SOL_SOCKET, SO_ERROR, (char *)&socket_error, &error_length);
    if (socket_error != 0) {
        elm_disconnect();
        return false;
    }
    nonblocking = 0;
    ioctlsocket(g_elm, FIONBIO, &nonblocking);

    char response[512];
    const char *init_commands[] = {"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"};
    for (unsigned i = 0; i < sizeof(init_commands) / sizeof(init_commands[0]); ++i) {
        if (!elm_command(init_commands[i], response, sizeof(response), i == 0 ? 1800 : 500)) {
            elm_disconnect();
            return false;
        }
    }
    g_elm_online = true;
    sim_ui_set_elm_connected(true);
    if (g_window) SetWindowTextA(g_window, "BRZ OBD Gauge - LVGL native - ELM327 TCP");
    return true;
}

static bool elm_pid(const char *pid, int *a, int *b)
{
    char command[8];
    char response[1024];
    snprintf(command, sizeof(command), "01%s", pid);
    if (!elm_command(command, response, sizeof(response), 500)) return false;

    char compact[1024];
    size_t used = 0;
    for (size_t i = 0; response[i] && used + 1 < sizeof(compact); ++i) {
        char c = response[i];
        if (c >= 'a' && c <= 'f') c = (char)(c - 'a' + 'A');
        if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) compact[used++] = c;
    }
    compact[used] = '\0';

    char marker[5];
    snprintf(marker, sizeof(marker), "41%s", pid);
    char *found = strstr(compact, marker);
    if (!found || strlen(found) < 6) return false;
    unsigned av = 0, bv = 0;
    if (sscanf(found + 4, "%2x%2x", &av, &bv) < 1) return false;
    *a = (int)av;
    *b = (int)bv;
    return true;
}

static int estimate_gear(int rpm, int speed)
{
    if (speed < 3 || rpm < 500) return 0;
    static const double rpm_per_kmh[] = {125.4, 75.7, 53.3, 41.9, 34.6, 26.5};
    double ratio = (double)rpm / speed;
    int best = 0;
    double best_error = 1e9;
    for (int i = 0; i < 6; ++i) {
        double error = fabs(ratio - rpm_per_kmh[i]);
        if (error < best_error) {
            best_error = error;
            best = i + 1;
        }
    }
    return best_error / rpm_per_kmh[best - 1] < 0.22 ? best : 0;
}

static void update_from_elm(void)
{
    static int query_index;
    static const char *pids[] = {"0C", "0D", "05", "0F", "5C", "04", "11", "10"};
    int a = 0, b = 0;
    if (!elm_pid(pids[query_index], &a, &b)) {
        elm_disconnect();
        if (g_window) SetWindowTextA(g_window, "BRZ OBD Gauge - LVGL native - demo (ELM reconnecting)");
        return;
    }
    switch (query_index) {
    case 0: g_data.rpm = (a * 256 + b) / 4; break;
    case 1: g_data.speed = a; break;
    case 2: g_data.coolant = a - 40; break;
    case 3: g_data.intake = a - 40; break;
    case 4: g_data.oil = a - 40; break;
    case 5: g_data.load = a * 100 / 255; break;
    case 6: g_data.throttle = a * 100 / 255; break;
    case 7: g_data.maf_x100 = a * 256 + b; break;
    }
    query_index = (query_index + 1) % 8;
    g_data.gear = estimate_gear(g_data.rpm, g_data.speed);
    g_data.oil_pressure = 105 + g_data.rpm * 65 / 1000;
}

static void update_demo(DWORD now)
{
    double t = now / 1000.0;
    g_data.rpm = 2900 + (int)(1900 * sin(t * 0.82)) + (int)(420 * sin(t * 2.1));
    if (g_data.rpm < 780) g_data.rpm = 780;
    g_data.speed = 64 + (int)(28 * sin(t * 0.31));
    g_data.coolant = 91 + (int)(3 * sin(t * 0.07));
    g_data.intake = 34 + (int)(4 * sin(t * 0.12));
    g_data.oil = 96 + (int)(6 * sin(t * 0.05));
    g_data.load = 44 + (int)(32 * sin(t * 0.82));
    g_data.throttle = 30 + (int)(24 * sin(t * 0.82));
    g_data.gear = estimate_gear(g_data.rpm, g_data.speed);
    g_data.oil_pressure = 105 + g_data.rpm * 65 / 1000;
    g_data.maf_x100 = 180 + (g_data.rpm * (g_data.load > 5 ? g_data.load : 5)) / 115;
}

static void update_ui(void)
{
    char text[24];
    snprintf(text, sizeof(text), "%d", g_data.rpm);
    lv_label_set_text(ui_RpmPageArcLabelRpmText, text);
    lv_arc_set_value(ui_RpmPageArcRpmBack, g_data.rpm);
    lv_label_set_text(ui_GearPageRpmText, text);
    ui_gear_page_update_visuals((uint16_t)g_data.rpm, (uint16_t)g_data.speed,
                                (uint8_t)g_data.gear,
                                nvs_cfg_get()->rpm_warn_threshold);

    snprintf(text, sizeof(text), "%d", g_data.speed);
    lv_label_set_text(ui_SpeedPageArcLabelSpeedText, text);
    lv_arc_set_value(ui_SpeedPageArcSpeedBack, (uint32_t)g_data.speed * 100 / SPEED_DIAL_MAX);

    snprintf(text, sizeof(text), "%d", g_data.gear);
    lv_label_set_text(ui_GearPageArcLabelGearNumText, g_data.gear ? text : "N");

    snprintf(text, sizeof(text), "%d", g_data.coolant);
    lv_label_set_text(ui_LabelTempValue[0], text);
    lv_label_set_text(ui_LabelInfoValue[0], text);
    snprintf(text, sizeof(text), "%d", g_data.intake);
    lv_label_set_text(ui_LabelTempValue[1], text);
    lv_label_set_text(ui_LabelInfoValue[4], text);
    snprintf(text, sizeof(text), "%d", g_data.oil);
    lv_label_set_text(ui_LabelTempValue[2], text);
    lv_label_set_text(ui_LabelInfoValue[1], text);
    lv_label_set_text(ui_NeedleValueLabel, text);
    lv_meter_set_indicator_value(ui_NeedleMeter, ui_NeedleIndic, g_data.oil);
    snprintf(text, sizeof(text), "%d", g_data.load);
    lv_label_set_text(ui_LabelInfoValue[2], text);
    snprintf(text, sizeof(text), "%d", g_data.throttle);
    lv_label_set_text(ui_LabelInfoValue[3], text);

    snprintf(text, sizeof(text), "%d", g_data.oil_pressure);
    lv_label_set_text(ui_LabelOilPressureText, text);
    lv_chart_set_next_value(ui_ChartOilPressure, ui_OilPressureChartSeries, g_data.oil_pressure);

    fuel_sample_t fuel_sample = {
        .maf_x100 = (uint16_t)g_data.maf_x100, .afr_x100 = 1470,
        .rpm = (uint16_t)g_data.rpm, .load_pct = (int16_t)g_data.load,
        .tps_pct = (int16_t)g_data.throttle, .speed_kmh = (uint8_t)g_data.speed,
        .rpm_valid = true, .speed_valid = true, .maf_valid = true,
        .fuel_status = 0, // unknown: never invent an ECU cutoff state in the preview
    };
    static DWORD last_fuel_ms;
    DWORD now_fuel_ms = GetTickCount();
    DWORD fuel_dt_ms = last_fuel_ms ? now_fuel_ms - last_fuel_ms : 0;
    last_fuel_ms = now_fuel_ms;
    nvs_fuel_update(&fuel_sample, fuel_dt_ms);
    ui_fuel_page_refresh();
    ui_trip_overview_refresh();
    ui_trip_history_refresh();
}

int main(int argc, char **argv)
{
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--headless") == 0) g_headless = true;
        else if (strcmp(argv[i], "--demo") == 0) g_force_demo = true;
        else if (strcmp(argv[i], "--elm-host") == 0 && i + 1 < argc) {
            strncpy(g_elm_host, argv[++i], sizeof(g_elm_host) - 1);
            g_elm_host[sizeof(g_elm_host) - 1] = '\0';
        }
        else if (strcmp(argv[i], "--elm-port") == 0 && i + 1 < argc) {
            g_elm_port = atoi(argv[++i]);
        }
    }
    WSADATA winsock;
    WSAStartup(MAKEWORD(2, 2), &winsock);

    g_framebuffer = calloc(SIM_W * SIM_H, sizeof(*g_framebuffer));
    if (!g_framebuffer) return 1;
    for (int i = 0; i < SIM_W * SIM_H; ++i) g_framebuffer[i] = 0xFF000000u;
    memset(&g_bitmap_info, 0, sizeof(g_bitmap_info));
    g_bitmap_info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    g_bitmap_info.bmiHeader.biWidth = SIM_W;
    g_bitmap_info.bmiHeader.biHeight = SIM_H;
    g_bitmap_info.bmiHeader.biPlanes = 1;
    g_bitmap_info.bmiHeader.biBitCount = 32;
    g_bitmap_info.bmiHeader.biCompression = BI_RGB;

    if (!g_headless && !create_window(GetModuleHandle(NULL))) return 2;

    lv_init();
    static lv_disp_draw_buf_t draw_buffer;
    static lv_color_t pixels[SIM_W * 48];
    lv_disp_draw_buf_init(&draw_buffer, pixels, NULL, SIM_W * 48);

    static lv_disp_drv_t display_driver;
    lv_disp_drv_init(&display_driver);
    display_driver.hor_res = SIM_W;
    display_driver.ver_res = SIM_H;
    display_driver.flush_cb = display_flush;
    display_driver.draw_buf = &draw_buffer;
    lv_disp_drv_register(&display_driver);

    if (!g_headless) {
        static lv_indev_drv_t input_driver;
        lv_indev_drv_init(&input_driver);
        input_driver.type = LV_INDEV_TYPE_POINTER;
        input_driver.read_cb = pointer_read;
        lv_indev_drv_register(&input_driver);
    }

    sim_ui_create_pages();

    DWORD previous = GetTickCount();
    DWORD last_data = 0;
    DWORD last_frame = 0;
    while (g_running) {
        MSG message;
        while (PeekMessage(&message, NULL, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessage(&message);
        }

        DWORD now = GetTickCount();
        DWORD elapsed = now - previous;
        previous = now;
        if (elapsed) lv_tick_inc(elapsed);

        if (now - last_data >= 100) {
            last_data = now;
            if (!g_force_demo && !g_elm_online && now - g_last_elm_retry >= 2000) {
                g_last_elm_retry = now;
                elm_connect();
            }
            if (!g_manual_data) {
                if (g_elm_online) update_from_elm();
                else update_demo(now);
            }
            update_ui();
            read_page_command();
            if (g_headless) save_status();
        }

        lv_timer_handler();
        if (g_headless && now - last_frame >= 80) {
            last_frame = now;
            save_framebuffer();
        }
        Sleep(5);
    }

    elm_disconnect();
    WSACleanup();
    free(g_framebuffer);
    return 0;
}
