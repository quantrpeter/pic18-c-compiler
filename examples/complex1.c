#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdbool.h>

/* 1. Preprocessor Macros & Conditional Compilation */
#define MAX_BUFFER 256
#define DEBUG_LOG(fmt, ...) printf("[DEBUG] " fmt "\n", ##__VA_ARGS__)

#ifdef __STDC_VERSION__
    #define C_VERSION __STDC_VERSION__
#else
    #define C_VERSION 0L
#endif

/* 2. Enumerations & Typedefs */
typedef enum {
    RES_IDLE    = 0,
    RES_ACTIVE  = 1,
    RES_ERROR   = 2,
    RES_UNKNOWN = 3
} Status_t;

/* 3. Structs with Bit-fields & Unions */
struct ResourceFlags {
    unsigned int is_priority : 1;
    unsigned int is_cached   : 1;
    unsigned int security_lvl : 3; // 0-7
    unsigned int reserved     : 3;
};

typedef struct {
    char name[32];
    Status_t status;
    struct ResourceFlags flags;
    
    union {
        int integer_data;
        float float_data;
        char *string_ptr;
    } payload;

    /* 4. Function Pointers */
    void (*process_callback)(void *data);
} Resource;

/* 5. Static & Volatile Qualifiers */
static int total_resources = 0; 
volatile bool hardware_interrupted = false; // Simulated hardware flag

/* 6. Variadic Function (stdarg.h) */
void log_event(const char *prefix, int count, ...) {
    va_list args;
    va_start(args, count);
    printf("%s: ", prefix);
    for (int i = 0; i < count; i++) {
        printf("%s ", va_arg(args, char*));
    }
    printf("\n");
    va_end(args);
}

/* 7. Advanced Function Signatures (const, restrict) */
void update_payload(Resource *restrict res, const char *new_val) {
    if (!res || !new_val) return;
    res->payload.string_ptr = strdup(new_val);
}

/* 8. Recursion & Pointer Arithmetic */
int factorial_recursive(int n) {
    if (n <= 1) return 1;
    return n * factorial_recursive(n - 1);
}

void print_buffer_hex(unsigned char *ptr, size_t len) {
    for (size_t i = 0; i < len; i++) {
        printf("%02x ", *(ptr + i)); // Pointer arithmetic
    }
    printf("\n");
}

/* 9. C11 Generic Selection (Macro Overloading) */
#define print_val(x) _Generic((x), \
    int: printf("Int: %d\n", x),    \
    float: printf("Float: %f\n", x), \
    default: printf("Unknown type\n") \
)

/* 10. Callback implementation */
void my_callback(void *data) {
    printf("Callback triggered for resource: %s\n", (char*)data);
}

int main(int argc, char *argv[]) {
    /* 11. Storage Classes & Scoping */
    register int i; 
    auto int local_var = 10;
    
    Resource *res_ptr = malloc(sizeof(Resource));
    if (!res_ptr) goto cleanup; // 12. Jump Statements (goto)

    // Initialization using Designated Initializers (C99)
    *res_ptr = (Resource){
        .name = "Kernel_Task",
        .status = RES_ACTIVE,
        .flags = { .is_priority = 1, .security_lvl = 5 },
        .process_callback = my_callback
    };

    // 13. Bitwise Operations & Ternary Operator
    unsigned char mask = 0xFF;
    int masked_val = (local_var > 5) ? (local_var & mask) : 0;

    // 14. Control Flow (Switch/Case)
    switch (res_ptr->status) {
        case RES_ACTIVE:
            res_ptr->process_callback(res_ptr->name);
            break;
        default:
            printf("Resource dormant.\n");
    }

    // 15. Do-While & Logical Operators
    int retry = 2;
    do {
        if (res_ptr->flags.is_priority && !hardware_interrupted) {
            DEBUG_LOG("Processing priority resource... (C Version: %ld)", C_VERSION);
        }
    } while (--retry > 0);

    // Testing Generics and Recursion
    print_val(42);
    print_val(3.14f);
    printf("Recursive result: %d\n", factorial_recursive(5));

    log_event("SYS_MSG", 3, "Auth", "Success", "Ready");

    // 16. Dynamic Memory & String Manipulation
    update_payload(res_ptr, "Encrypted_Data_Stream");
    printf("Payload: %s\n", res_ptr->payload.string_ptr);

    print_buffer_hex((unsigned char*)res_ptr->name, 8);

    free(res_ptr->payload.string_ptr);
    free(res_ptr);

    return 0;

cleanup:
    fprintf(stderr, "Fatal memory error.\n");
    return EXIT_FAILURE;
}