Recursion is a technique in which a method solves a problem by calling itself with a smaller version of that same problem. It is powerful when the problem has a natural “smaller problem” shape, but it needs a reliable stopping rule.

## Define a base case and a recursive case

```java
long factorial(int nonNegativeNumber) {
    if (nonNegativeNumber < 0) {
        throw new IllegalArgumentException("Factorial requires a non-negative number.");
    }
    if (nonNegativeNumber <= 1) {
        return 1;
    }
    return nonNegativeNumber * factorial(nonNegativeNumber - 1);
}

void main() {
    int numberOfChoices = 4;
    IO.println(numberOfChoices + "! = " + factorial(numberOfChoices));
}
```

The base case is `nonNegativeNumber <= 1`. It returns a known answer without another recursive call. The recursive case multiplies by the current number and asks for the factorial of one smaller number. Because the input moves toward the base case, the method terminates for every non-negative input.

## Trace the calls

For `factorial(4)`, Java must wait for smaller calls to finish before it can multiply the answers:

```
factorial(4)
= 4 * factorial(3)
= 4 * 3 * factorial(2)
= 4 * 3 * 2 * factorial(1)
= 24
```

Each pending method call has its own parameters and local variables. Java keeps those pending calls on the call stack. Once `factorial(1)` returns `1`, the calls complete in reverse order.

## Check the three recursion rules

Every recursive method needs all three of these properties:

1. A base case that returns without calling the method again.
2. A recursive case that calls the same method.
3. Progress toward the base case on every recursive call.

If the base case is missing or the argument never gets closer to it, the calls continue until the program exhausts the call stack. Even a correct recursive algorithm can use too much stack space for a very deep input, so choose recursion when the problem's structure makes the smaller-problem rule clear.

The factorial example uses `long` only for small teaching examples; factorials grow quickly and eventually exceed its range. As practice, write a recursive `sumThrough` method that returns the sum from a non-negative number down to zero. State its base case and how its recursive call makes progress before writing the code.
