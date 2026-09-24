package ru.lct.heating.geometry;

/**
 * Форма сетки поиска пути (ADR-0041). Квадратная сетка даёт 8 направлений
 * (оси + диагонали), гексагональная — 6 равных направлений, лучше подходящих
 * для геометрий, расположенных под углом к осям. Клетки хранятся в общей
 * растровой матрице {@code row * width + col}; форма задаёт центры, соседей и
 * преобразование координат.
 */
public interface GridShape {

    String id();

    /** Число столбцов сетки для полосы шириной {@code widthM}. */
    int columns(double widthM, double cellM);

    /** Число строк сетки для полосы высотой {@code heightM}. */
    int rows(double heightM, double cellM);

    double centerX(int col, int row, double originX, double cellM);

    double centerY(int col, int row, double originY, double cellM);

    /** Клетка, содержащая точку; возвращает {@code [col, row]}. */
    int[] cell(double x, double y, double originX, double originY, double cellM,
               int columns, int rows);

    /**
     * Смещения соседних клеток {@code [dcol, drow]} в зависимости от строки.
     * Возвращается общий неизменяемый массив (без аллокаций в горячем пути).
     */
    int[][] neighbors(int col, int row);

    /** Диагональный ли шаг (для квадрата — проверка углового проскока). */
    boolean diagonal(int dcol, int drow);

    double stepLength(double cellM, int dcol, int drow);

    /** Вертикальный шаг строк, м. */
    double rowSpacing(double cellM);

    /** Консервативное расширение препятствия (для устаревшей двухуровневой маски). */
    double conservativeDilation(double cellM);
}
