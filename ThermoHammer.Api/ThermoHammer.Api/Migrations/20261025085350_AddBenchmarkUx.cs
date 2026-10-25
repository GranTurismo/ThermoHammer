using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkUx : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_BenchmarkRuns_BenchmarkVersion_Score",
                table: "BenchmarkRuns");

            migrationBuilder.AddColumn<double>(
                name: "MlKitQrRate",
                table: "BenchmarkRuns",
                type: "float",
                nullable: true);

            migrationBuilder.AddColumn<double>(
                name: "QrRate",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "QrScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<int>(
                name: "TotalScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<int>(
                name: "UxScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "WebCanvas",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "WebCss",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "WebJs",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "WebLayout",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "WebScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "WebSvg",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.CreateIndex(
                name: "IX_BenchmarkRuns_BenchmarkVersion_TotalScore",
                table: "BenchmarkRuns",
                columns: new[] { "BenchmarkVersion", "TotalScore" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_BenchmarkRuns_BenchmarkVersion_TotalScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "MlKitQrRate",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "QrRate",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "QrScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "TotalScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "UxScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "WebCanvas",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "WebCss",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "WebJs",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "WebLayout",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "WebScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "WebSvg",
                table: "BenchmarkRuns");

            migrationBuilder.CreateIndex(
                name: "IX_BenchmarkRuns_BenchmarkVersion_Score",
                table: "BenchmarkRuns",
                columns: new[] { "BenchmarkVersion", "Score" });
        }
    }
}
