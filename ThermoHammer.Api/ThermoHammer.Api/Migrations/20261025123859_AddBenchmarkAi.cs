using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkAi : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<double>(
                name: "AiClassifyCpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiClassifyGpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiClassifyNpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "AiCpuScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "AiDepthCpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiDepthGpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiDepthNpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiDetectCpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiDetectGpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiDetectNpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "AiGpuScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<string>(
                name: "AiNpuRuntime",
                table: "BenchmarkRuns",
                type: "nvarchar(max)",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "AiNpuScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<int>(
                name: "AiScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "AiSegmentCpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiSegmentGpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiSegmentNpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiSoundCpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiSoundGpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiSoundNpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiUpscaleCpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiUpscaleGpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "AiUpscaleNpu",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "AiClassifyCpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiClassifyGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiClassifyNpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiCpuScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiDepthCpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiDepthGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiDepthNpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiDetectCpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiDetectGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiDetectNpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiGpuScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiNpuRuntime",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiNpuScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiSegmentCpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiSegmentGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiSegmentNpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiSoundCpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiSoundGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiSoundNpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiUpscaleCpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiUpscaleGpu",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "AiUpscaleNpu",
                table: "BenchmarkRuns");
        }
    }
}
