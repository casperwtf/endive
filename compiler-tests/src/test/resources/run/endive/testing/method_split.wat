;; Functions exercising MethodSplit: each is compiled whole and split at several segment budgets,
;; and every split must return what the interpreter returns.
(module
  (tag $e (param i32))
  (memory 1)
  (global $g (mut i32) (i32.const 0))
  (elem declare func $thrower)

  ;; a bytecode interpreter: a loop around a br_table dispatch with one arm per opcode
  (func (export "dispatch") (param $n i32) (result i64)
    (local $pc i32) (local $op i32) (local $k i32) (local $i i32)
    (local $acc i64) (local $f f64) (local $g f32)
    (local.set $acc (i64.const 1))
    (local.set $g (f32.const 1))
    (block $done
      (loop $next
        (local.set $pc (i32.add (local.get $pc) (i32.const 1)))
        (br_if $done (i32.gt_u (local.get $pc) (local.get $n)))
        (block $b19
        (block $b18
        (block $b17
        (block $b16
        (block $b15
        (block $b14
        (block $b13
        (block $b12
        (block $b11
        (block $b10
        (block $b9
        (block $b8
        (block $b7
        (block $b6
        (block $b5
        (block $b4
        (block $b3
        (block $b2
        (block $b1
        (block $b0
          (br_table $b0 $b1 $b2 $b3 $b4 $b5 $b6 $b7 $b8 $b9 $b10 $b11 $b12 $b13 $b14 $b15 $b16 $b17 $b18 $b19 $b0 (i32.rem_u (i32.mul (local.get $pc) (i32.const 7)) (i32.const 20))))
      ;; arm 0
      (local.set $acc (i64.add (i64.mul (local.get $acc) (i64.const 31)) (i64.extend_i32_u (local.get $pc))))
      (local.set $k (i32.xor (local.get $k) (i32.const 1)))
      (br $next)
      )
      ;; arm 1
      (local.set $f (f64.add (local.get $f) (f64.convert_i32_u (local.get $pc))))
      (local.set $g (f32.mul (local.get $g) (f32.const 1.5)))
      (local.set $acc (i64.sub (local.get $acc) (i64.const 1)))
      )
      ;; arm 2
      (if (i32.eq (local.get $pc) (i32.const 42))
        (then (return (i64.add (local.get $acc) (i64.const 1002)))))
      (local.set $acc (i64.rotl (local.get $acc) (i64.const 3)))
      (br $next)
      )
      ;; arm 3
      (local.set $acc
        (i64.add (local.get $acc)
          (block $carry (result i64)
            (br_if $carry (i64.const 9) (i32.and (local.get $pc) (i32.const 1)))
            (drop)
            (local.set $k (i32.add (local.get $k) (i32.const 3)))
            (i64.extend_i32_s (local.get $k)))))
      )
      ;; arm 4
      (local.set $i (i32.const 0))
      (loop $inner
        (local.set $acc (i64.add (local.get $acc) (i64.extend_i32_u (local.get $i))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br_if $inner (i32.lt_u (local.get $i) (i32.const 6))))
      (br_if $done (i64.gt_u (local.get $acc) (i64.const 0x7000000000000000)))
      )
      ;; arm 5
      (local.set $acc (i64.add (i64.mul (local.get $acc) (i64.const 31)) (i64.extend_i32_u (local.get $pc))))
      (local.set $k (i32.xor (local.get $k) (i32.const 36)))
      (br $next)
      )
      ;; arm 6
      (local.set $f (f64.add (local.get $f) (f64.convert_i32_u (local.get $pc))))
      (local.set $g (f32.mul (local.get $g) (f32.const 1.5)))
      (local.set $acc (i64.sub (local.get $acc) (i64.const 6)))
      )
      ;; arm 7
      (if (i32.eq (local.get $pc) (i32.const 47))
        (then (return (i64.add (local.get $acc) (i64.const 1007)))))
      (local.set $acc (i64.rotl (local.get $acc) (i64.const 8)))
      (br $next)
      )
      ;; arm 8
      (local.set $acc
        (i64.add (local.get $acc)
          (block $carry (result i64)
            (br_if $carry (i64.const 24) (i32.and (local.get $pc) (i32.const 1)))
            (drop)
            (local.set $k (i32.add (local.get $k) (i32.const 8)))
            (i64.extend_i32_s (local.get $k)))))
      )
      ;; arm 9
      (local.set $i (i32.const 0))
      (loop $inner
        (local.set $acc (i64.add (local.get $acc) (i64.extend_i32_u (local.get $i))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br_if $inner (i32.lt_u (local.get $i) (i32.const 4))))
      (br_if $done (i64.gt_u (local.get $acc) (i64.const 0x7000000000000000)))
      )
      ;; arm 10
      (local.set $acc (i64.add (i64.mul (local.get $acc) (i64.const 31)) (i64.extend_i32_u (local.get $pc))))
      (local.set $k (i32.xor (local.get $k) (i32.const 71)))
      (br $next)
      )
      ;; arm 11
      (local.set $f (f64.add (local.get $f) (f64.convert_i32_u (local.get $pc))))
      (local.set $g (f32.mul (local.get $g) (f32.const 1.5)))
      (local.set $acc (i64.sub (local.get $acc) (i64.const 11)))
      )
      ;; arm 12
      (if (i32.eq (local.get $pc) (i32.const 52))
        (then (return (i64.add (local.get $acc) (i64.const 1012)))))
      (local.set $acc (i64.rotl (local.get $acc) (i64.const 13)))
      (br $next)
      )
      ;; arm 13
      (local.set $acc
        (i64.add (local.get $acc)
          (block $carry (result i64)
            (br_if $carry (i64.const 39) (i32.and (local.get $pc) (i32.const 1)))
            (drop)
            (local.set $k (i32.add (local.get $k) (i32.const 13)))
            (i64.extend_i32_s (local.get $k)))))
      )
      ;; arm 14
      (local.set $i (i32.const 0))
      (loop $inner
        (local.set $acc (i64.add (local.get $acc) (i64.extend_i32_u (local.get $i))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br_if $inner (i32.lt_u (local.get $i) (i32.const 2))))
      (br_if $done (i64.gt_u (local.get $acc) (i64.const 0x7000000000000000)))
      )
      ;; arm 15
      (local.set $acc (i64.add (i64.mul (local.get $acc) (i64.const 31)) (i64.extend_i32_u (local.get $pc))))
      (local.set $k (i32.xor (local.get $k) (i32.const 106)))
      (br $next)
      )
      ;; arm 16
      (local.set $f (f64.add (local.get $f) (f64.convert_i32_u (local.get $pc))))
      (local.set $g (f32.mul (local.get $g) (f32.const 1.5)))
      (local.set $acc (i64.sub (local.get $acc) (i64.const 16)))
      )
      ;; arm 17
      (if (i32.eq (local.get $pc) (i32.const 57))
        (then (return (i64.add (local.get $acc) (i64.const 1017)))))
      (local.set $acc (i64.rotl (local.get $acc) (i64.const 5)))
      (br $next)
      )
      ;; arm 18
      (local.set $acc
        (i64.add (local.get $acc)
          (block $carry (result i64)
            (br_if $carry (i64.const 54) (i32.and (local.get $pc) (i32.const 1)))
            (drop)
            (local.set $k (i32.add (local.get $k) (i32.const 18)))
            (i64.extend_i32_s (local.get $k)))))
      )
      ;; arm 19
      (local.set $i (i32.const 0))
      (loop $inner
        (local.set $acc (i64.add (local.get $acc) (i64.extend_i32_u (local.get $i))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br_if $inner (i32.lt_u (local.get $i) (i32.const 7))))
      (br_if $done (i64.gt_u (local.get $acc) (i64.const 0x7000000000000000)))
        (br $next)))
    (i64.xor (local.get $acc)
      (i64.xor (i64.trunc_sat_f64_s (local.get $f))
        (i64.extend_i32_u (i32.reinterpret_f32 (local.get $g))))))

  (func $thrower (param $x i32)
    (if (i32.and (local.get $x) (i32.const 1)) (then (throw $e (local.get $x)))))

  ;; locals assigned in a segment before a throw are seen by the catch in the root
  (func (export "throwLocals") (param $x i32) (result i32)
    (local $a i32) (local $b i32) (local $c i64)
    (block $h (result i32)
      (try_table (catch $e $h)
        (local.set $a (i32.const 10))
        (local.set $b (i32.add (local.get $a) (local.get $x)))
        (local.set $c (i64.const 5))
        (call $thrower (local.get $x))
        (local.set $a (i32.const 1000))
        (local.set $c (i64.const 6)))
      (return (i32.add (i32.add (local.get $a) (local.get $b)) (i32.wrap_i64 (local.get $c)))))
    (i32.mul (i32.const 100))
    (i32.add (local.get $a))
    (i32.add (local.get $b))
    (i32.add (i32.wrap_i64 (local.get $c))))

  ;; a try_table inside a segment whose catch branches out of the segment
  (func (export "catchOut") (param $x i32) (result i32)
    (local $t i32)
    (block $outer (result i32)
      (local.set $t (i32.const 3))
      (block $h (result i32)
        (local.set $t (i32.add (local.get $t) (local.get $x)))
        (try_table (catch $e $h)
          (local.set $t (i32.mul (local.get $t) (i32.const 2)))
          (call $thrower (local.get $x)))
        (local.set $t (i32.add (local.get $t) (i32.const 1)))
        (br $outer (local.get $t)))
      (i32.add (local.get $t))))

  ;; an exnref local kept in the frame and rethrown from a segment
  (func (export "exnref") (param $x i32) (result i32)
    (local $ex exnref) (local $r i32)
    (block $outer (result i32)
      (try_table (catch $e $outer)
        (local.set $ex
          (block $h (result exnref)
            (try_table (catch_all_ref $h)
              (call $thrower (local.get $x)))
            (return (i32.const -1))))
        (local.set $r (i32.const 5))
        (local.set $r (i32.add (local.get $r) (i32.const 2)))
        (throw_ref (local.get $ex)))
      (i32.const -2))
    (i32.add (local.get $r)))

  ;; return with several values from inside a segment
  (func $multi (export "multi") (param $x i32) (result i32 i64)
    (local $i i32)
    (loop $l
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (global.set $g (i32.add (global.get $g) (i32.const 1)))
      (if (i32.eq (local.get $i) (local.get $x))
        (then (return (local.get $i) (i64.extend_i32_s (i32.mul (local.get $i) (i32.const -3))))))
      (br_if $l (i32.lt_s (local.get $i) (i32.const 100))))
    (i32.const -1)
    (i64.const -1))

  (func (export "multiSum") (param $x i32) (result i64)
    (local $a i32) (local $b i64)
    (call $multi (local.get $x))
    (local.set $b)
    (local.set $a)
    (i64.add (local.get $b) (i64.extend_i32_s (local.get $a))))

  ;; a branch carrying a value out of a segment to a block in the root
  (func (export "carry") (param $x i32) (result i32)
    (local $t i32)
    (block $out (result i32)
      (local.set $t (i32.mul (local.get $x) (i32.const 2)))
      (local.set $t (i32.sub (local.get $t) (i32.const 1)))
      (br_if $out (i32.const 77) (i32.gt_s (local.get $t) (i32.const 10)))
      (drop)
      (local.set $t (i32.add (local.get $t) (i32.const 1)))
      (local.set $t (i32.mul (local.get $t) (local.get $t)))
      (local.get $t)))

  ;; a funcref local defaults to null in the frame
  (func (export "funcrefDefault") (param $x i32) (result i32)
    (local $f funcref) (local $k i32)
    (local.set $k (i32.const 3))
    (local.set $k (i32.mul (local.get $k) (local.get $x)))
    (if (local.get $x) (then (local.set $f (ref.func $thrower))))
    (i32.add (ref.is_null (local.get $f)) (local.get $k)))

  ;; float bit patterns, NaN payloads included, survive the frame
  (func (export "floatBits") (param $bits i32) (result i64)
    (local $f f32) (local $d f64) (local $k i32)
    (local.set $f (f32.reinterpret_i32 (local.get $bits)))
    (local.set $d (f64.reinterpret_i64 (i64.or (i64.const 0x7ff0000000000000) (i64.extend_i32_u (local.get $bits)))))
    (local.set $k (i32.const 1))
    (local.set $k (i32.add (local.get $k) (i32.const 1)))
    (i64.xor
      (i64.shl (i64.extend_i32_u (i32.reinterpret_f32 (local.get $f))) (i64.const 32))
      (i64.reinterpret_f64 (local.get $d))))

  ;; if/else bodies and a loop whose body starts with a segment
  (func (export "branches") (param $x i32) (result i32)
    (local $i i32) (local $s i32)
    (if (i32.gt_s (local.get $x) (i32.const 5))
      (then
        (local.set $s (i32.const 1))
        (local.set $s (i32.add (local.get $s) (local.get $x))))
      (else
        (local.set $s (i32.const 2))
        (local.set $s (i32.sub (local.get $s) (local.get $x)))))
    (loop $l
      (local.set $s (i32.add (local.get $s) (local.get $i)))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br_if $l (i32.lt_s (local.get $i) (local.get $x))))
    (local.get $s))

  ;; a statement ending unreachable: the root must not fall through after the call
  (func (export "unreachableTail") (param $x i32) (result i32)
    (local $t i32)
    (block $b
      (local.set $t (i32.add (local.get $x) (i32.const 1)))
      (if (local.get $x) (then (br $b)))
      (local.set $t (i32.const 9))
      (return (local.get $t)))
    (local.get $t))
)
