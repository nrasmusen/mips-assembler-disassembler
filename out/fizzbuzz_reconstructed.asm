.data
D_10010000: .asciiz "Fizz"
D_10010005: .asciiz "Buzz"
D_1001000A: .asciiz "FizzBuzz"

.text
    addiu $t1, $zero, 1
    addiu $t0, $zero, 101
L_00400008:
    j     L_0040001C
L_0040000C:
    j     L_0040009C
L_00400010:
    addi  $t1, $t1, 1
    bne   $t1, $t0, L_00400008
    j     L_004000AC
L_0040001C:
    addiu $t2, $zero, 15
    div   $t1, $t2
    mfhi  $t3
    beq   $t3, $zero, L_00400050
    addiu $t2, $zero, 3
    div   $t1, $t2
    mfhi  $t3
    beq   $t3, $zero, L_00400064
    addiu $t2, $zero, 5
    div   $t1, $t2
    mfhi  $t3
    beq   $t3, $zero, L_00400078
    j     L_0040008C
L_00400050:
    addiu $v0, $zero, 4
    lui   $at, 0x1001
    ori   $a0, $at, 0x000A
    syscall
    j     L_0040000C
L_00400064:
    addiu $v0, $zero, 4
    lui   $at, 0x1001
    ori   $a0, $at, 0x0000
    syscall
    j     L_0040000C
L_00400078:
    addiu $v0, $zero, 4
    lui   $at, 0x1001
    ori   $a0, $at, 0x0005
    syscall
    j     L_0040000C
L_0040008C:
    addiu $v0, $zero, 1
    addu  $a0, $zero, $t1
    syscall
    j     L_0040000C
L_0040009C:
    addiu $a0, $zero, 10
    addiu $v0, $zero, 11
    syscall
    j     L_00400010
L_004000AC:
    addiu $v0, $zero, 10
    syscall
