.data
fizz: .asciiz "Fizz"
buzz: .asciiz "Buzz"
fizzBuzz: .asciiz "FizzBuzz"
 
.text
li $t1, 1
li $t0, 101
 
loop:
j PrintItem
 
PrintItemEnd:
j PrintNewLine
 
PrintNewLineEnd:
addi $t1, $t1, 1
bne $t1, $t0, loop
j Exit
 
PrintItem:
# Divisible by 15 or 5 and 3
li $t2, 15
div $t1, $t2
mfhi $t3
beq $t3, $zero, PrintFizzBuzz
# Divisible by 3
li $t2, 3
div $t1, $t2
mfhi $t3
beq $t3, $zero, PrintFizz
# Divisible by 5
li $t2, 5
div $t1, $t2
mfhi $t3
beq $t3, $zero, PrintBuzz
# Isn't divisible by any
j PrintInt
 
PrintFizzBuzz:
li $v0, 4
la $a0, fizzBuzz
syscall
j PrintItemEnd
 
PrintFizz:
li $v0, 4
la $a0, fizz
syscall
j PrintItemEnd
 
PrintBuzz:
li $v0, 4
la $a0, buzz
syscall
j PrintItemEnd
 
PrintInt:
li $v0, 1
move $a0, $t1
syscall
j PrintItemEnd
 
PrintNewLine:
li $a0, '\n'
li $v0, 11
syscall
j PrintNewLineEnd
 
Exit:
li $v0, 10
syscall