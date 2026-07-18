## Solve a problem by solving a smaller one

Recursion is a technique in which a method calls itself with a smaller version of the same problem. It fits problems with a natural smaller-problem shape, but it needs a reliable stopping rule.

## Define a base case and a recursive case

Save this program as `FactorialDemo.java`, then run `java FactorialDemo.java`.

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

Expected output:

```text
4! = 24
```

The base case, `nonNegativeNumber <= 1`, returns a known answer without another recursive call. The recursive case multiplies by the current number and asks for the factorial of one smaller number. Each call moves toward the base case, so every non-negative input terminates.

## Trace pending calls

For `factorial(4)`, Java waits for smaller calls to finish before it can multiply their answers:

```text
factorial(4)
= 4 * factorial(3)
= 4 * 3 * factorial(2)
= 4 * 3 * 2 * factorial(1)
= 24
```

Each pending method call has its own parameters and local variables. Java keeps those calls on the call stack. After `factorial(1)` returns `1`, the calls complete in reverse order.

## Apply the same pattern to a sum

Save this program as `SumThroughDemo.java`, then run `java SumThroughDemo.java`.

```java
int sumThrough(int nonNegativeNumber) {
    if (nonNegativeNumber < 0) {
        throw new IllegalArgumentException("The sum requires a non-negative number.");
    }
    if (nonNegativeNumber == 0) {
        return 0;
    }
    return nonNegativeNumber + sumThrough(nonNegativeNumber - 1);
}

void main() {
    int finalLessonNumber = 4;
    IO.println("Total through lesson " + finalLessonNumber + ": " + sumThrough(finalLessonNumber));
}
```

Expected output:

```text
Total through lesson 4: 10
```

Here the base case is `sumThrough(0)`, and every recursive call uses one smaller non-negative number.

## Check the three recursion rules

Every recursive method needs all three properties:

1. A base case that returns without another recursive call.
2. A recursive case that calls the same method.
3. Progress toward the base case on every recursive call.

If a base case is missing or an argument does not get closer to it, calls continue until the program exhausts the call stack. Even a correct recursive method can use too much stack space for a deeply nested input, so use recursion when the smaller-problem rule is clear.

`long` keeps the factorial example useful only for small teaching inputs: factorial values grow quickly and eventually exceed its range.

## Check your understanding

- Trace `sumThrough(3)` on paper before running it.
- State the base case and progress rule for a recursive method that counts down from a positive number to zero.
- Explain why changing `nonNegativeNumber - 1` to `nonNegativeNumber + 1` breaks the recursion rule.
