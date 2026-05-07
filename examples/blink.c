// Blink LED on RB0 of PIC18F4520.
//
// This program is the canonical "hello world" for the pic18-c-compiler.
// On a real PIC18F4520 you also need to clear TRISB so PORTB pins are
// outputs; this is *not* emitted by the compiler -- the user is expected
// to set up TRIS bits manually in the generated assembly (see README).
//
// Built-ins recognised by the compiler:
//   void out(int port, int value);   // port 0..4 -> LATA..LATE
//   int  in(int port);               // port 0..4 -> PORTA..PORTE
//   void delay(int n);               // nested decrement loop, n in [0,255]

void delay(int n);
void out(int port, int value);

int main(void) {
    int i;
    out(1, 0x00);              // LATB <- 0  (LEDs off; RB0 will toggle below)
    while (1) {
        out(1, 0x01);          // LATB <- 0x01 (RB0 high)
        delay(200);
        out(1, 0x00);          // LATB <- 0x00 (RB0 low)
        delay(200);
    }
    int b=0;
    for (int a=0;a<10+b/2;a++){
        out(1, 0x01);          // LATB <- 0x01 (RB0 high)
        delay(200);
        out(1, 0x00);          // LATB <- 0x00 (RB0 low)
        delay(200);
        b++;
    }
    return 0;
}
