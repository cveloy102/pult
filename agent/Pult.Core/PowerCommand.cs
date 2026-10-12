namespace Pult.Core;

public static class PowerCommand
{
    public static string[] Arguments(string action) => action switch
    {
        "shutdown" => ["/s", "/t", "0"],
        "restart" => ["/r", "/t", "0"],
        _ => throw new ArgumentException("Команда питания должна быть shutdown или restart.", nameof(action))
    };
}
