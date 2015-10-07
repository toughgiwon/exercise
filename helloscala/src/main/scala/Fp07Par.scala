class ExecutorService {
    def submit[A](a: Callable[A]): Future[A]
}

trait Callable[A] { def call: A }

trait Future[A] {
    def get: A
    def get(timeout: Long, unit: TimeUnit): A
    def cancel(evenIfRunning: Boolean): Boolean
    def isDone: Boolean
    def isCancelled: Boolean
}


type Par[A] = ExecutorService => Future[A]

object Par {
    def unit[A](a: A): Par[A] = (es: ExecutorService) => UnitFuture(s)

    private case class UnitFuture[A](get: A) extends Future[A] {
        def isDone = true
        def get(timeout: Long, units: TimeUnit) = get
        def isCancelled = false
        def cancel(evenIfRunning: Boolean): Boolean = false
    }

    // af.get, bf.get 에서 timeout 경우에 대한 처리가 없다
    // 끝나기 전에 받아서 isDone / cancel / get(timeout) 해볼 수가 없고 
    // 이 구현은 반드시 a, b 두 작업이 끝나야 리턴한다 (사실상 Furue가 아님)
    def map2[A, B, C](a: Par[A], b: Par[B])(f: (A,B) => C): Par[C] =
        (es: ExecutorService) => {
            val af = a(es)
            val bf = b(es)
            UnitFuture( f(af.get, bf.get) )
        }

    // 정의할 때 
    def fork[A](a: => Par[A]): Par[A] =
        es => es.submit( new Callable[A] {
            def call = a(es).get
        } )

    // Ex3
    def map2Ex3[A, B, C](a: Par[A], b: Par[B])(f: (A,B) => C): Par[C] =
        (es: ExecutorService) => {
            val af = a(es)
            val bf = b(es)
            Map2Future( af, bf, f )
        }

    case class Map2Future[A,B,C](a: Future[A], b: Future[B],
            f: (A, B) => C)  extends Future[C] {
        @volatile var cache: Option[C] = None

        def isDone = cache.isDefined
        def isCancelled = a.isCancelled || b.isCancelled
        def cancel(evenIfRunning: Boolean) =
            a.cancel(evenIfRunning) || b.cancel(evenIfRunning)
        def get = x_get(Long.MaxValue)
        def get(timeout: Long, units: TimeUnit): C =
            x_get(TimeUnit.NANOSECONDS.convert(timeout, units))

        private def x_get(timeoutInNanos: Long): C = cache match {
            case Some(c) => c
            case None => {
                val start = System.nanoTime
                val ar = a.get(timeoutInNanos, TimeUnit.NANOSECONDS)
                val stop = System.nanoTime
                val aTime = stop - start
                val br = b.get(timeoutInNanos - aTime, TimeUnit.NANOSECONDS)
                val ret = f(ar, br)
                cache = Some(ret)
                ret
            }
        }
    }

    // Ex4
    // A => B 인 함수를 인자로 받아서 A => Par[B] 인 함수를 리턴
    def asyncF[A,B](f: A => B): A => Par[B] =
        (a: A) => lazyUnit( f(a) )

    def sortPar(parList: Par[List[Int]]): Par[List[Int]] =
        map2( parList, unit(()) )( (a, _) => a.sorted )

    // A=>B 변환 기존 함수를 이용하여 잠재적표현식[A]=>잠재적표현식[B] 변환
    def map[A,B](pa: Par[A])(f: A => B): Par[B] =
        map2( pa, unit(()) )( (a, _) => f(a) )

    def sortPar(parList: Par[List[Int]]): Par[List[Int]] =
        map( parList )( _.sorted )

    // map2 에서 map 을 만들어내는 것을 보면
    // 기본 수단이라고 생각했던 함수가 다른 좀 더 근본적인 기본수단들로
    // 표현할 수 있음을 자주 느끼게 된다


    // 목록의 각 원소에 변환 A => B 적용하여 Par[List[B]] 를 구함
    // 주의: List[Par[B]] 가 아님
    // 중간단계에서 나오는 List[Par[B]] 를 Par[List[B]] 로 바꿔주는 로직 필요
    def parMap[A,B](ps: List[A])(f: A => B): Par[List[B]] = fork {
        val fps: List[Par[B]] = ps.map(asyncF(f))
        sequence(fps)
    }

    // Ex5
    // foldLeft( 초기값: 빈목록, 반복적용함수: 빈목록에
    // Par[A] => A 함수는 run 만 있었던 것 같은데...

    def sequence[A](ps: List[Par[A]]): Par[List[A]]

}

