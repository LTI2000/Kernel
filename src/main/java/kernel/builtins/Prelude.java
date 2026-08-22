package kernel.builtins;

/**
 * The prelude: derived operatives, written in Kernel itself.
 *
 * <p>Everything here could have been written in Java, and in a Lisp with
 * macros most of it would have to be built into the expander. In Kernel
 * these are ordinary values, derived from $vau, wrap and eval.
 */
final class Prelude {

    private Prelude() {}

    static final String SOURCE = """
            ; ($lambda formals . body) == (wrap ($vau formals #ignore . body))
            ($define! $lambda
              ($vau (formals . body) env
                (wrap (eval (cons $vau (cons formals (cons #ignore body))) env))))

            ; An applicative that returns its (already evaluated) argument list.
            ($define! list (wrap ($vau args #ignore args)))

            ($define! list*
              ($lambda (head . tail)
                ($if (null? tail)
                     head
                     (cons head (apply list* tail)))))

            ($define! $let
              ($vau (bindings . body) env
                (eval (cons (list* $lambda (map car bindings) body)
                            (map cadr bindings))
                      env)))

            ($define! $cond
              ($vau clauses env
                ($if (null? clauses)
                     #inert
                     ($let ((clause (car clauses)))
                       ($if (eval (car clause) env)
                            (eval (cons $sequence (cdr clause)) env)
                            (eval (cons $cond (cdr clauses)) env))))))

            ($define! $when
              ($vau (test . body) env
                ($if (eval test env) (eval (cons $sequence body) env) #inert)))

            ($define! $unless
              ($vau (test . body) env
                ($if (eval test env) #inert (eval (cons $sequence body) env))))

            ($define! $and?
              ($vau clauses env
                ($cond ((null? clauses)       #t)
                       ((null? (cdr clauses)) (eval (car clauses) env))
                       ((eval (car clauses) env) (eval (cons $and? (cdr clauses)) env))
                       (#t                    #f))))

            ($define! $or?
              ($vau clauses env
                ($cond ((null? clauses)       #f)
                       ((null? (cdr clauses)) (eval (car clauses) env))
                       ((eval (car clauses) env) #t)
                       (#t                    (eval (cons $or? (cdr clauses)) env)))))

            ($define! $let*
              ($vau (bindings . body) env
                (eval ($if (null? bindings)
                           (list* $let bindings body)
                           (list $let
                                 (list (car bindings))
                                 (list* $let* (cdr bindings) body)))
                      env)))

            ($define! $letrec
              ($vau (bindings . body) env
                (eval (list* $let ()
                             (list $define!
                                   (map car bindings)
                                   (cons list (map cadr bindings)))
                             body)
                      env)))

            ($define! $letrec*
              ($vau (bindings . body) env
                (eval ($if (null? bindings)
                           (list* $letrec bindings body)
                           (list $letrec
                                 (list (car bindings))
                                 (list* $letrec* (cdr bindings) body)))
                      env)))

            ; ($set! env-expr ptree value-expr): define in a remote environment.
            ($define! $set!
              ($vau (env-expr ptree value-expr) env
                (eval (list $define! ptree (list (unwrap eval) value-expr env))
                      (eval env-expr env))))

            ($define! for-each ($lambda args (apply map args) #inert))
            """;
}
