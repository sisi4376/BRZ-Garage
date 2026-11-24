#include "app_obd_dsp/obd_group.h"
#include <assert.h>

static unsigned s_slow_poll_index, s_group_failures[2];
static int s_fuel_status_pid_support;
static const char *expected[8];
static unsigned sent;

static void elm327_ble_send_ascii_blocking(const char *command)
{
    assert(sent < 8 && expected[sent]);
    assert(strcmp(expected[sent++], command) == 0);
}

#include "export_path/ui_rounding.h"
typedef struct { char text[32]; } lv_obj_t;
static const char *lv_label_get_text(lv_obj_t *label) { return label->text; }
static void lv_label_set_text(lv_obj_t *label, const char *value)
{
    snprintf(label->text, sizeof(label->text), "%s", value);
}
/* DISPLAY_VALUE */
/* POLL_GROUP */

int main(void)
{
    lv_obj_t label = {0};
    const char *angles[] = {"-64.0", "-0.5", "0.0", "0.5", "63.5", "--"};
    const int16_t raw[] = {-128, -1, 0, 1, 127, INT16_MIN};
    for (unsigned i = 0; i < sizeof(raw) / sizeof(raw[0]); ++i) {
        disp_item_set_text(&label, DISP_ITEM_IGN, raw[i], raw[i] != INT16_MIN);
        assert(strcmp(label.text, angles[i]) == 0);
    }
    const uint8_t group_b[] = {0x0C, 0x0D, 0x10, 0x44, 0x0E, 0x0F};
    obd_group_value_t values[6];
    const char *numbered = "010\r0: 41 0C 0C 80 0D 20\r1: 10 01 F4 44 80 00 0E\r2: 7F 0F 44\r>";
    assert(group_decode(numbered, group_b, 6, values) == 6);
    assert(values[4].pid == 0x0E && values[4].data[0] == 127);
    assert(group_decode("410E800C10000D20>", group_b, 6, values) == 3);
    assert(group_decode("410E80 0E80>", group_b, 6, values) == 0);
    assert(group_decode("410E80 4400>", group_b, 6, values) == 0);
    assert(group_decode("410E80 1100>", group_b, 6, values) == 0);
    assert(group_decode("010\r0: 410C0C800D20\r2: 1001F44480000E\r>", group_b, 6, values) == 0);
    assert(group_decode("NO DATA\r>", group_b, 6, values) == 0);

    const char *slow[] = {"0F", "05", "5C", "42", "2F", "5E", "40"};
    for (unsigned toyota = 0; toyota < 2; ++toyota) {
        for (unsigned i = 0; i < 7; ++i) {
            s_fuel_status_pid_support = 1;
            sent = 0;
            expected[0] = "010C0D10110403\r";
            poll_pid_group(false, toyota);
            assert(sent == 1);
            sent = 0;
            const char *extra = i == 6 ? "0140\r" : (i == 2 && toyota ? "2101\r" : NULL);
            char command[16];
            snprintf(command, sizeof(command), "010C0D10440E%s\r", extra ? "" : slow[i]);
            expected[0] = command;
            expected[1] = extra;
            poll_pid_group(true, toyota);
            assert(sent == (extra ? 2u : 1u));
        }
    }
    s_group_failures[0] = 2;
    s_fuel_status_pid_support = 0;
    const char *singles[] = {"010C\r","010D\r","0110\r","0111\r","0104\r","0144\r"};
    memcpy(expected, singles, sizeof(singles));
    sent = 0;
    poll_pid_group(false, false);
    assert(sent == 6);
}
