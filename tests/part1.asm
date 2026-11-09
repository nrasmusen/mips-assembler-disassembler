# Part 1 instructions: the assembler's output must match the hand-assembled answers
# 0x20080003, 0x20090005, 0x01095020, 0x01285822
.text
addi $t0, $zero, 3
addi $t1, $zero, 5
add  $t2, $t0, $t1
sub  $t3, $t1, $t0
